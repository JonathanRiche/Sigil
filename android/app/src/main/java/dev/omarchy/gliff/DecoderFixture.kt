package dev.omarchy.gliff

import android.content.res.AssetManager

/** Synthetic testsrc2 frames generated locally by FFmpeg/libx264. No remote content. */
object DecoderFixture {
    fun accessUnits(assets: AssetManager): List<ByteArray> {
        val data = assets.open("decoder-test.h264").use { it.readBytes() }
        require(data.size <= 256 * 1024)
        val delimiters = ArrayList<Int>()
        var i = 0
        while (i + 4 < data.size) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte()) {
                val prefix = if (data[i+2] == 1.toByte()) 3 else if (data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) 4 else 0
                if (prefix > 0) {
                    if ((data[i+prefix].toInt() and 31) == 9) delimiters.add(i)
                    i += prefix; continue
                }
            }
            i++
        }
        require(delimiters.size == 30) { "Invalid local decoder fixture" }
        return delimiters.mapIndexed { n, start -> data.copyOfRange(start,delimiters.getOrNull(n+1) ?: data.size) }
    }
}
