package dev.omarchy.gliff

import java.util.Locale

object HostIdentity {
    fun key(host: String, port: Int): String {
        val canonical = host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        return "${canonical.length}:$canonical:$port"
    }
    fun validPin(value: String): Boolean = try {
        value.startsWith("SHA256:") && value.length == 50 &&
            java.util.Base64.getDecoder().decode(value.substring(7)).let {
                it.size == 32 && java.util.Base64.getEncoder().withoutPadding().encodeToString(it) == value.substring(7)
            }
    } catch (_: IllegalArgumentException) { false }
    fun approvalUrl(value: String): Boolean = try {
        val uri = java.net.URI(value)
        value.length <= 4096 && uri.scheme == "https" && uri.host == "login.tailscale.com" &&
            uri.rawUserInfo == null && uri.port in listOf(-1,443) && uri.path.startsWith("/a/") && uri.fragment == null
    } catch (_: Exception) { false }
}
