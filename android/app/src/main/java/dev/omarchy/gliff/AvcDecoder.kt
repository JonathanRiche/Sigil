package dev.omarchy.gliff

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecList
import android.view.Surface
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer

/** Owned exclusively by the session worker. Annex B access units, Single420 only. */
class AvcDecoder(private val surface: Surface, private val output: (Long) -> Unit, private val rendered: (Long) -> Unit, private val diagnostics: StreamDiagnostics? = null) : AutoCloseable {
    private var codec: MediaCodec? = null
    @Volatile private var generation = 0
    private var width = 0
    private var height = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val info = MediaCodec.BufferInfo()
    private var sequenceInfo: AvcSps.Info? = null
    fun configure(w: Int, h: Int) {
        require(w in 2..4096 && h in 2..4096 && w.toLong() * h <= 3840L * 2160) { "Unsupported video dimensions" }
        close()
        width = w; height = h; sps = null; pps = null; sequenceInfo = null
    }
    fun frame(data: ByteArray, pts: Long): Boolean {
        require(data.size <= 8 * 1024 * 1024) { "Video access unit too large" }
        val parameters = AnnexB.parameters(data) {
            val parsed = AvcSps.validate(it,width,height)
            sequenceInfo?.let { old -> require(parsed.profile == old.profile && parsed.level <= old.level) { "AVC profile/level changed without decoder reset" } }
            if (sequenceInfo == null) sequenceInfo = parsed
        }
        parameters.sps?.let { sps = it }
        parameters.pps?.let { pps = it }
        if (codec == null) {
            val sequence = sps ?: return false
            val picture = pps ?: return false
            if (!parameters.idr) return false
            val parsed = checkNotNull(sequenceInfo)
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sequence)); setByteBuffer("csd-1", ByteBuffer.wrap(picture))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
                setInteger(MediaFormat.KEY_PROFILE, when (parsed.profile) {
                    66 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    77 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    88 -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileExtended
                    else -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                })
                setInteger(MediaFormat.KEY_LEVEL, level(parsed.level))
            }
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val name = codecs.findDecoderForFormat(format) ?: error("No AVC decoder supports this stream")
            val capabilities = codecs.codecInfos.first { it.name == name }.getCapabilitiesForType("video/avc")
            val lowLatency = capabilities.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            diagnostics?.codec = "$name,profile=${parsed.profile},level=${parsed.level}"
            diagnostics?.lowLatency = if (lowLatency) "requested" else "unsupported"
            if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY,1)
            codec = MediaCodec.createByCodecName(name).also {
                try {
                    it.configure(format, surface, null, 0)
                    val currentGeneration = generation
                    it.setOnFrameRenderedListener({ _, pts, _ ->
                        if (generation == currentGeneration) rendered(pts)
                    }, Handler(Looper.getMainLooper()))
                    it.start()
                } catch (e: Exception) { it.release(); throw e }
            }
        }
        val c = codec!!
        diagnostics?.phase = "dequeue-input"
        var index = c.dequeueInputBuffer(10_000)
        var retries = 0
        while (index < 0 && retries++ < 100) { drain(c); diagnostics?.phase = "dequeue-input"; index = c.dequeueInputBuffer(10_000) }
        check(index >= 0) { "Decoder stalled" }
        val buffer = c.getInputBuffer(index)!!
        require(buffer.capacity() >= data.size) { "Decoder input capacity exceeded" }
        buffer.clear(); buffer.put(data)
        diagnostics?.phase = "queue-input"
        c.queueInputBuffer(index, 0, data.size, pts, 0)
        diagnostics?.accepted()
        drain(c)
        return true
    }
    fun pump() { codec?.let { drain(it) } }
    private fun drain(c: MediaCodec) {
        while (true) {
            diagnostics?.phase = "dequeue-output"
            val index = c.dequeueOutputBuffer(info, 0)
            if (index >= 0) {
                diagnostics?.phase = "release-output"
                c.releaseOutputBuffer(index, true); diagnostics?.output(); output(info.presentationTimeUs)
            }
            else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) validateOutput(c.outputFormat)
            else break
        }
        diagnostics?.phase = "decoder-idle"
    }
    private fun validateOutput(format: MediaFormat) {
        fun number(key: String) = if (format.containsKey(key)) format.getInteger(key).toString() else "not-exposed"
        diagnostics?.outputFormat = "${number(MediaFormat.KEY_WIDTH)}x${number(MediaFormat.KEY_HEIGHT)},outputDelay=${number("output-delay")},internalDelay=${number("android._output-delay")},reorder=${number("output-reorder-depth")},lowLatency=${number(MediaFormat.KEY_LOW_LATENCY)}"
        val sequence = checkNotNull(sequenceInfo)
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        require(w in 2..sequence.codedWidth && h in 2..sequence.codedHeight) { "Decoder output dimensions exceed SPS" }
        val left = if (format.containsKey("crop-left")) format.getInteger("crop-left") else 0
        val right = if (format.containsKey("crop-right")) format.getInteger("crop-right") else w-1
        val top = if (format.containsKey("crop-top")) format.getInteger("crop-top") else 0
        val bottom = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") else h-1
        require(left >= 0 && top >= 0 && right < w && bottom < h && right-left+1 == width && bottom-top+1 == height) {
            "Decoder output does not match stream configuration"
        }
    }
    override fun close() { generation++; codec?.let { try { it.stop() } finally { it.release() } }; codec = null }
    companion object {
        private fun level(value: Int): Int {
            val levels = intArrayOf(10,11,12,13,20,21,22,30,31,32,40,41,42,50,51,52)
            // Android AVC level constants are consecutive power-of-two flags, with 1b between1 and1.1.
            val index = levels.indexOf(value)
            require(index >= 0)
            return 1 shl (if (index == 0) 0 else index+1)
        }
        fun supportsSize(w: Int, h: Int): Boolean {
            val format = MediaFormat.createVideoFormat("video/avc",w,h).apply {
                setInteger(MediaFormat.KEY_PROFILE,android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val name = list.findDecoderForFormat(format) ?: return false
            val codec = list.codecInfos.firstOrNull { it.name == name } ?: return false
            return codec.getCapabilitiesForType("video/avc").videoCapabilities.isSizeSupported(w,h)
        }
        fun capabilities(): Pair<Int,Int> {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for ((w,h) in listOf(1920 to 1080,1280 to 720,640 to 480)) {
                val format = MediaFormat.createVideoFormat("video/avc",w,h).apply {
                    setInteger(MediaFormat.KEY_PROFILE,android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                }
                if (list.findDecoderForFormat(format) != null) return w to h
            }
            error("No supported H.264 High profile decoder")
        }
    }
}
