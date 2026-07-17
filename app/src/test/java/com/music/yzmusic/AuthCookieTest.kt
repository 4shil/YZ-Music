package com.music.yzmusic

import com.music.yzmusic.auth.AuthStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a captured cookie header actually carries a secret Innertube requests
 * can be signed with.
 *
 * Pinned because the failure this replaces was silent in both directions and
 * cost users their listening history. The old test was
 * `cookie.contains("SAPISID")`, and the string `__Secure-3PAPISID` contains
 * `SAPISID` — so a cookie jar holding only the `__Secure-` forms passed a check
 * for a cookie it did not have. The app then reported itself signed in, sent the
 * cookie, and sent no `Authorization` header, which Google answers as a stranger:
 * the library degraded quietly and no play was ever written to history.
 *
 * The reverse mistake is just as bad and is what makes the name/value split
 * necessary rather than fussy — a substring test is satisfied by a cookie whose
 * *value* happens to contain the text, or by a name that merely ends in it.
 */
class AuthCookieTest {

    @Test
