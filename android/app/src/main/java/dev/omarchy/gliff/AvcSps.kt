package dev.omarchy.gliff

/** Minimal bounded AVC SPS reader for progressive 8-bit 4:2:0, profiles B/M/E/High. */
object AvcSps {
    data class Info(val profile: Int, val level: Int, val codedWidth: Int, val codedHeight: Int,
        val width: Int, val height: Int, val referenceFrames: Int)
    private class Bits(private val bytes: ByteArray) {
        private var bit = 0
        fun read(n: Int): Int {
            require(n in 0..24 && bit.toLong()+n <= bytes.size.toLong()*8) { "Truncated AVC SPS" }
            var value = 0
            repeat(n) { value = (value shl 1) or ((bytes[bit/8].toInt() ushr (7-bit%8)) and 1); bit++ }
            return value
        }
        fun ue(max: Int): Int {
            var zeros = 0
            while (read(1) == 0) { require(++zeros <= 16) { "Oversized AVC SPS integer" } }
            val value = (1 shl zeros)-1+read(zeros)
            require(value <= max) { "AVC SPS value exceeds supported limit" }
            return value
        }
        fun se(max: Int): Int { val code = ue(max*2); return if ((code and 1) != 0) (code+1)/2 else -code/2 }
    }
    fun parse(nal: ByteArray): Info {
        require(nal.size in 8..65540 && nal.take(4) == listOf<Byte>(0,0,0,1) && (nal[4].toInt() and 31) == 7 && (nal[4].toInt() and 128) == 0) { "Invalid AVC SPS" }
        val rbsp = ByteArray(nal.size-5)
        var count = 0; var zeros = 0
        for (i in 5 until nal.size) {
            val value = nal[i].toInt() and 255
            if (zeros >= 2 && value == 3) { require(i+1 < nal.size && (nal[i+1].toInt() and 255) <= 3); zeros = 0; continue }
            rbsp[count++] = nal[i]
            zeros = if (value == 0) zeros+1 else 0
        }
        val b = Bits(rbsp.copyOf(count))
        val profile = b.read(8); val constraints = b.read(8); val level = b.read(8)
        require((constraints and 3) == 0) { "Invalid AVC SPS constraints" }
        require(profile in listOf(66,77,88,100)) { "Unsupported AVC profile" }
        require(level in listOf(10,11,12,13,20,21,22,30,31,32,40,41,42,50,51,52)) { "Unsupported AVC level" }
        b.ue(31)
        if (profile == 100) {
            require(b.ue(3) == 1) { "Only AVC 4:2:0 is supported" }
            require(b.ue(6) == 0 && b.ue(6) == 0) { "Only 8-bit AVC is supported" }
            b.read(1)
            if (b.read(1) != 0) repeat(8) { index ->
                if (b.read(1) != 0) {
                    var last = 8; var next = 8
                    repeat(if (index < 6) 16 else 64) {
                        if (next != 0) next = (last+b.se(128)+256)%256
                        if (next != 0) last = next
                    }
                }
            }
        }
        b.ue(12)
        when (b.ue(2)) {
            0 -> b.ue(12)
            1 -> { b.read(1); b.se(32767); b.se(32767); repeat(b.ue(255)) { b.se(32767) } }
        }
        val references = b.ue(16); b.read(1)
        val codedWidth = (b.ue(255)+1)*16
        val mapHeight = b.ue(255)+1
        require(b.read(1) == 1) { "Interlaced AVC is unsupported" }
        val codedHeight = mapHeight*16
        b.read(1)
        var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
        if (b.read(1) != 0) { cropLeft = b.ue(2048); cropRight = b.ue(2048); cropTop = b.ue(2048); cropBottom = b.ue(2048) }
        val width = codedWidth-2*(cropLeft+cropRight)
        val height = codedHeight-2*(cropTop+cropBottom)
        require(width >= 2 && height >= 2 && codedWidth.toLong()*codedHeight <= 3840L*2160) { "AVC SPS dimensions exceed limits" }
        var buffers = 16
        if (b.read(1) != 0) {
            if (b.read(1) != 0 && b.read(8) == 255) { require(b.read(16) > 0 && b.read(16) > 0) }
            if (b.read(1) != 0) b.read(1)
            if (b.read(1) != 0) { b.read(3); b.read(1); if (b.read(1) != 0) b.read(24) }
            if (b.read(1) != 0) { b.ue(5); b.ue(5) }
            if (b.read(1) != 0) { repeat(4) { b.read(16) }; b.read(1) }
            fun hrd() {
                val count = b.ue(31)+1; b.read(8)
                repeat(count) { b.ue(65535); b.ue(65535); b.read(1) }
                b.read(20)
            }
            val nalHrd = b.read(1) != 0; if (nalHrd) hrd()
            val vclHrd = b.read(1) != 0; if (vclHrd) hrd()
            if (nalHrd || vclHrd) b.read(1)
            b.read(1)
            if (b.read(1) != 0) {
                b.read(1); repeat(4) { b.ue(16) }
                val reorder = b.ue(16); buffers = b.ue(16)
                require(buffers >= references && reorder <= buffers) { "Invalid AVC decoded-buffer limits" }
            }
        }
        require(codedWidth.toLong()*codedHeight*(buffers+4)*3/2 <= 128L*1024*1024) { "AVC decoded-buffer budget exceeded" }
        return Info(profile,level,codedWidth,codedHeight,width,height,references)
    }
    fun validate(nal: ByteArray, width: Int, height: Int): Info = parse(nal).also {
        require(it.width == width && it.height == height) { "AVC SPS dimensions do not match stream configuration" }
    }
}
