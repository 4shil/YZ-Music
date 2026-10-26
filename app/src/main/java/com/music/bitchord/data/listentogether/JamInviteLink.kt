package com.music.bitchord.data.listentogether

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class ParsedJamInvite(
    val code: String,
    val serverUrl: String? = null,
)

/** Relays a YZ Music web or scheme invite from [com.music.bitchord.MainActivity] to Compose. */
object JamInviteLink {

    const val ORIGIN = "https://yz-music.vercel.app"
    const val ALT_ORIGIN = "https://yz-music.vercel.com"

    private const val EXTRA_CONSUMED = "yzmusic.jamInviteConsumed"
    private const val HOST = "yz-music.vercel.app"
    private const val ALT_HOST = "yz-music.vercel.com"
    private const val LEGACY_HOST = "bitchord.kushagrasingh.in"
    private const val CUSTOM_SCHEME = "yzmusic"
    private const val LEGACY_SCHEME = "bitchord"
    private const val CUSTOM_HOST = "party"

    private val _pending = MutableStateFlow<ParsedJamInvite?>(null)
    val pending: StateFlow<ParsedJamInvite?> = _pending.asStateFlow()

    /** Reads a web invite from a cold launch or a new intent on the existing task. */
    fun consume(intent: Intent?): Boolean {
        if (
            intent == null ||
            intent.action != Intent.ACTION_VIEW ||
            intent.getBooleanExtra(EXTRA_CONSUMED, false)
        ) return false

        val invite = parseInvite(intent.dataString) ?: return false
        intent.putExtra(EXTRA_CONSUMED, true)
        _pending.value = invite
        return true
    }

    fun handled() {
        _pending.value = null
    }

    /** Returns the normalized party code only for the public invite URL shape. */
    fun parse(value: String?): String? = parseInvite(value)?.code

    /**
     * Parses an incoming invite:
     * 1. yzmusic://party/<CODE>?server=<SERVER> or bitchord://party/<CODE>?server=<SERVER>
     * 2. https://yz-music.vercel.app/invite/<CODE>?server=<SERVER>
     * 3. https://yz-music.vercel.com/invite/<CODE>?server=<SERVER>
     * 4. https://bitchord.kushagrasingh.in/invite/<CODE>?server=<SERVER>
     */
    fun parseInvite(value: String?): ParsedJamInvite? {
        val uri = runCatching { URI(value ?: return null) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val query = uri.rawQuery
        val server = extractQueryParam(query, "server")?.let { sanitizeServerUrl(it) }

        // 1. Custom scheme: yzmusic://party/<CODE> or bitchord://party/<CODE>
        if ((scheme == CUSTOM_SCHEME || scheme == LEGACY_SCHEME) && host == CUSTOM_HOST) {
            val pathPart = uri.path.orEmpty().trim('/').takeIf { it.isNotBlank() }
            val candidate = pathPart ?: extractQueryParam(query, "code") ?: return null
            val code = cleanCode(candidate) ?: return null
            return ParsedJamInvite(code = code, serverUrl = server)
        }

        // 2. Official web domain: yz-music.vercel.app, yz-music.vercel.com, or legacy bitchord.kushagrasingh.in
        if (scheme == "https" && (host == HOST || host == ALT_HOST || host == LEGACY_HOST)) {
            val match = INVITE_PATH.matchEntire(uri.path.orEmpty()) ?: return null
            val code = match.groupValues[1].uppercase()
            return ParsedJamInvite(code = code, serverUrl = server)
        }

        return null
    }

    fun url(code: String, customServer: String? = null): String {
        val base = customServer?.trim()?.trimEnd('/')
        return if (!base.isNullOrBlank() && !base.equals(ORIGIN, ignoreCase = true) && !base.equals(ALT_ORIGIN, ignoreCase = true)) {
            "$base/invite/${code.uppercase()}"
        } else {
            "$ORIGIN/invite/${code.uppercase()}"
        }
    }

    fun schemeUrl(code: String, customServer: String? = null): String {
        val normalizedCode = code.uppercase()
        val base = customServer?.trim()?.trimEnd('/')
        return if (!base.isNullOrBlank()) {
            val encoded = runCatching { URLEncoder.encode(base, "UTF-8") }.getOrDefault(base)
            "yzmusic://party/$normalizedCode?server=$encoded"
        } else {
            "yzmusic://party/$normalizedCode"
        }
    }

    private fun cleanCode(raw: String): String? {
        val cleaned = raw.filter { it.isLetterOrDigit() }.uppercase()
        return if (cleaned.length == ListenTogether.CODE_LENGTH) cleaned else null
    }

    private fun extractQueryParam(query: String?, paramName: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&').asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.isNotEmpty() && it[0].equals(paramName, ignoreCase = true) }
            ?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    private fun sanitizeServerUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        return withScheme
    }

    private val INVITE_PATH = Regex("""/invite/([A-Za-z0-9]{${ListenTogether.CODE_LENGTH}})""")
}

