package com.music.bitchord.data.listentogether

/**
 * The public invite URL for a party, and the only shape that is accepted back.
 *
 * Shared because both applications have to agree on it exactly: a link built on one and opened on
 * the other must resolve to the same code, and a link that is not this shape must be refused on
 * both rather than half-parsed into a join attempt.
 */
object JamInvite {

    const val ORIGIN = "https://yz-music.vercel.app"
    const val ALT_ORIGIN = "https://yz-music.vercel.com"
    const val LEGACY_ORIGIN = "https://bitchord.kushagrasingh.in"

    /** How many characters a party code carries. */
    const val CODE_LENGTH = 6

    /** The normalized party code, for the public invite URL shape only. */
    fun parse(value: String?): String? {
        val text = value?.trim() ?: return null
        val code = when {
            text.startsWith("$ORIGIN/invite/", ignoreCase = true) ->
                text.removePrefix("$ORIGIN/invite/")
            text.startsWith("$ALT_ORIGIN/invite/", ignoreCase = true) ->
                text.removePrefix("$ALT_ORIGIN/invite/")
            text.startsWith("$LEGACY_ORIGIN/invite/", ignoreCase = true) ->
                text.removePrefix("$LEGACY_ORIGIN/invite/")
            else -> return null
        }.substringBefore('?').substringBefore('#')

        if (code.length != CODE_LENGTH || !code.all(Char::isLetterOrDigit)) return null
        return code.uppercase()
    }

    fun url(code: String): String = "$ORIGIN/invite/${code.uppercase()}"
}
