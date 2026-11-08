package dev.omarchy.gliff

/** Explicit destination only: no ambient SSH config, user, port, or credentials. */
data class ConnectionDestination(val user: String, val host: String) {
    companion object {
        fun parse(value: String): ConnectionDestination? {
            val text = value.trim(); val at = text.indexOf('@')
            if (at <= 0 || at != text.lastIndexOf('@') || at == text.lastIndex) return null
            val user = text.substring(0,at); var host = text.substring(at+1)
            if (user.any { it.isWhitespace() || it == '/' || it == '\\' }) return null
            if (host.startsWith('[') && host.endsWith(']')) host = host.substring(1,host.length-1)
            if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' || it == '\\' || it == '[' || it == ']' }) return null
            if (host.count { it == ':' } == 1) return null // A port belongs in Advanced.
            return ConnectionDestination(user,host)
        }
    }
}
