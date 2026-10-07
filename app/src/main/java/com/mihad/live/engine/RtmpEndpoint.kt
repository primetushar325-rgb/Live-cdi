package com.mihad.live.engine

import java.net.URI
import java.net.URISyntaxException

/** Pure validation/build helper. Neither methods log nor retain the supplied secret. */
object RtmpEndpoint {

    data class Validation(val valid: Boolean, val message: String? = null)

    fun validateServer(server: String): Validation {
        val trimmed = server.trim()
        if (trimmed.isEmpty()) return Validation(false, "RTMP server URL is required")
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return Validation(false, "RTMP server URL is invalid")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("rtmp", "rtmps")) {
            return Validation(false, "Use an RTMP or RTMPS server URL")
        }
        if (uri.host.isNullOrBlank()) return Validation(false, "RTMP server URL must include a host")
        if (uri.userInfo != null) return Validation(false, "Do not put a stream key or password in the server URL")
        if (uri.query != null || uri.fragment != null) {
            return Validation(false, "RTMP server URL must not include a query or fragment")
        }
        if (uri.path.isNullOrBlank() || uri.path == "/") {
            return Validation(false, "RTMP server URL must include its application path")
        }
        return Validation(true)
    }

    fun validateKey(key: String): Validation {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return Validation(false, "Stream key is missing")
        if (trimmed.any { it.isWhitespace() }) return Validation(false, "Stream key cannot contain spaces")
        if (trimmed.any { it in "/?#" }) return Validation(false, "Stream key must not contain URL path or query separators")
        if (trimmed.startsWith("rtmp://", true) || trimmed.startsWith("rtmps://", true)) {
            return Validation(false, "Paste only the stream key in the Stream Key field")
        }
        return Validation(true)
    }

    /** Build in memory at the moment of publishing. The returned URL is secret-bearing. */
    fun build(server: String, key: String): String {
        val serverCheck = validateServer(server)
        require(serverCheck.valid) { serverCheck.message ?: "RTMP server URL is invalid" }
        val keyCheck = validateKey(key)
        require(keyCheck.valid) { keyCheck.message ?: "Stream key is missing" }
        return "${server.trim().trimEnd('/')}/${key.trim().trimStart('/')}"
    }
}
