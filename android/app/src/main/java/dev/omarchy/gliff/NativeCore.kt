package dev.omarchy.gliff

/** Explicit session bridge. See INTERFACE.md. */
object NativeCore {
    val available: Boolean = try {
        System.loadLibrary("gliff_android_core")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun openSession(host: String, port: Int, user: String, privateKey: String, passphrase: String, fingerprint: String, maxWidth: Int, maxHeight: Int): Long
    external fun openSessionWithMode(host: String, port: Int, user: String, privateKey: String, passphrase: String, fingerprint: String, maxWidth: Int, maxHeight: Int, headless: Boolean): Long
    external fun remoteAction(handle: Long, kind: Int, identity: String): Boolean
    external fun openSessionOnOutput(host: String, port: Int, user: String, privateKey: String, passphrase: String, fingerprint: String, maxWidth: Int, maxHeight: Int, output: String): Long
    external fun pollEvent(handle: Long): ByteArray?
    external fun sendControl(handle: Long, kind: Int, a: Double, b: Double, c: Int, d: Int): Boolean
    external fun setClipboard(handle: Long, text: String): Boolean
    external fun requestClipboard(handle: Long): Boolean
    external fun closeSession(handle: Long)
    external fun viewport(width: Int, height: Int): Long
}
