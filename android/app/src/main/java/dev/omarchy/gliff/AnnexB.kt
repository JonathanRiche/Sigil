package dev.omarchy.gliff

/** Bounded scanning validates every SPS; retains the first SPS/PPS for decoder setup. */
object AnnexB {
    data class Parameters(val sps: ByteArray?, val pps: ByteArray?, val idr: Boolean)
    fun parameters(data: ByteArray, validateSps: (ByteArray) -> Unit = {}): Parameters {
        require(data.size <= 8*1024*1024)
        var sps: ByteArray? = null; var pps: ByteArray? = null; var idr = false
        var start = -1; var prefix = 0; var count = 0; var parameterCount = 0; var i = 0
        fun nal(end: Int) {
            if (start < 0 || end <= start+prefix) return
            val type = data[start+prefix].toInt() and 31
            if (type == 5) idr = true
            require(type != 15) { "AVC subset SPS is unsupported" }
            if (type == 7 || type == 8) {
                require(++parameterCount <= 16) { "Too many AVC parameter sets" }
                val size = end-start-prefix
                require(size <= 65536) { "AVC parameter set too large" }
                val bytes = byteArrayOf(0,0,0,1)+data.copyOfRange(start+prefix,end)
                if (type == 7) { validateSps(bytes); if (sps == null) sps = bytes } else if (pps == null) pps = bytes
            }
        }
        while (i+3 < data.size) {
            val n = if (data[i] == 0.toByte() && data[i+1] == 0.toByte()) {
                if (data[i+2] == 1.toByte()) 3 else if (data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) 4 else 0
            } else 0
            if (n > 0) {
                require(++count <= 4096) { "Too many AVC NAL units" }
                nal(i); start = i; prefix = n; i += n
            } else i++
        }
        nal(data.size)
        return Parameters(sps,pps,idr)
    }
}
