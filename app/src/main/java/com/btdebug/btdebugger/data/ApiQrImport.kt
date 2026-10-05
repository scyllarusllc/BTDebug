package com.btdebug.btdebugger.data

import java.net.URLDecoder

data class ApiImportConfiguration(val url: String, val token: String)

object ApiQrImport {
    fun parse(raw: String): ApiImportConfiguration {
        require(raw.length <= 16_384) { "QR configuration is too large." }
        val text = raw.trim()
        val configuration = if (text.startsWith("btdebug://", ignoreCase = true)) {
            val content = text.substring("btdebug://".length)
            val separator = content.indexOf('@')
            require(separator >= 0) { "Expected btdebug://<token>@<HTTP or HTTPS API URL>." }
            val token = runCatching {
                // Percent-decode only the token; '+' is a literal plus, not a space.
                URLDecoder.decode(content.substring(0, separator).replace("+", "%2B"), "UTF-8")
            }.getOrElse { throw IllegalArgumentException("Token contains invalid URL encoding.") }
            ApiImportConfiguration(content.substring(separator + 1), token)
        } else {
            ApiImportConfiguration(text, "")
        }
        WeightApi.validationError(configuration.url, configuration.token)?.let {
            throw IllegalArgumentException(it)
        }
        return configuration
    }
}
