package com.mihad.live.engine

import java.net.URI
import java.net.URISyntaxException

/** Pure validation/build helper. Neither methods log nor retain the supplied secret. */
object RtmpEndpoint {

    data class Validation(val valid: Boolean, val message: String? = null)

    fun validateServer(server: String): Validation {
        if (server.isBlank()) return Validation(false, "RTMP server URL is required")
        if (server != server.trim()) {
            return Validation(false, "Remove leading or trailing spaces from the RTMP server URL")
        }
        val uri = try {
            URI(server)
        } catch (_: URISyntaxException) {
            return Validation(false, "RTMP server URL is invalid")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("rtmp", "rtmps")) {
            return Validation(false, "Use an RTMP or RTMPS server URL")
        }
        if (uri.host.isNullOrBlank()) return Validation(false, "RTMP server URL must include a host")
        if (uri.rawUserInfo != null) return Validation(false, "Do not put a stream key or password in the server URL")
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return Validation(false, "RTMP server URL must not include a query or fragment")
        }
        if (uri.rawPath.isNullOrBlank() || uri.rawPath == "/") {
            return Validation(false, "RTMP server URL must include its application path")
        }
        return Validation(true)
    }

    fun validateKey(key: String): Validation {
        if (key.isEmpty()) return Validation(false, "Stream key is missing")
        if (key != key.trim() || key.any { it.isWhitespace() }) {
            return Validation(false, "Remove spaces from the stream key and paste the exact key")
        }
        if (!key.matches(Regex("[A-Za-z0-9._~!\$&'()*+,;=:@-]+"))) {
            return Validation(false, "Stream key contains characters that cannot be used as one RTMP stream-name segment")
        }
        return Validation(true)
    }

    /**
     * RootEncoder accepts a complete RTMP publish URL, so the separate user-provided key
     * is appended as one raw, unescaped path segment. The entered server prefix is preserved
     * byte-for-byte; only the separator is omitted when it is already present.
     */
    fun build(server: String, key: String): String {
        val serverCheck = validateServer(server)
        require(serverCheck.valid) { serverCheck.message ?: "RTMP server URL is invalid" }
        val keyCheck = validateKey(key)
        require(keyCheck.valid) { keyCheck.message ?: "Stream key is missing" }
        return server + (if (server.endsWith('/')) "" else "/") + key
    }
}
