package com.music.yzmusic

import com.music.yzmusic.data.lyrics.ScriptRoutingTransliterator
import com.music.yzmusic.data.lyrics.ScriptTransliterator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The routing decision: which engine gets a line.
 *
 * Both engines here are fakes that return their own name, so the assertions
 * are about *which one was asked*, not about what either of them would really
 * produce. That is the point of the class — it holds no romanization of its own.
 */
class ScriptRoutingTransliteratorTest {

    private val seen = mutableListOf<String>()

    private val fake = object : ScriptTransliterator {
        override fun toLatin(text: String): String? {
            seen += text
            return text
        }
    }

    private val router = ScriptRoutingTransliterator(japanese = fake, other = fake)

    private fun routes(text: String): String {
        seen.clear()
        router.toLatin(text)
        return seen.single()
    }

    @Test
    fun `kana goes to the Japanese engine`() {
        // こんにちは and さくら contain no Han at all, so a Han test would miss
        // the most common case in the catalogue outright.
        assertEquals("こんにちは", routes("こんにちは"))
        assertEquals("さくら", routes("さくら"))
        assertEquals("サクラ", routes("サクラ"))
    }

    @Test
    fun `a line mixing kana and kanji goes to the Japanese engine`() {
        assertEquals("そんな顔が嫌いだ", routes("そんな顔が嫌いだ"))
    }

    @Test
    fun `han on its own goes to the other engine`() {
        // Chinese lyrics are Han too, and ICU is right for them. Routing Han to
        // the Japanese engine by default would break every Chinese song to fix
        // a kanji-only Japanese line the lexicon already mostly covers.
        assertEquals("我爱你", routes("我爱你"))
    }

    @Test
    fun `other scripts go to the other engine`() {
        assertEquals("안녕하세요", routes("안녕하세요"))
        assertEquals("Привет", routes("Привет"))
        assertEquals("مرحبا", routes("مرحبا"))
        assertEquals("Ελλάδα", routes("Ελλάδα"))
    }

    @Test
    fun `latin inside a song comes back unchanged`() {
        // The rule that makes the split per *line* rather than per song. A
        // Japanese song with an English hook must not be sent to the kana rules
        // for the whole track.
        assertEquals("I don't wanna say goodbye", routes("I don't wanna say goodbye"))
        assertEquals("Fade away", routes("Fade away"))
    }

    @Test
    fun `an empty line is not routed anywhere`() {
        // Both engines are asked to do work they have nothing to do with if an
        // empty line is passed through, and a romanizer that returns an empty
        // string is the pipeline's cue to drop the line.
        seen.clear()
        router.toLatin("")
        assertEquals(emptyList<String>(), seen)
    }
}
