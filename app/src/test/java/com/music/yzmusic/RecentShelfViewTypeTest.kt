package com.music.yzmusic

import android.os.Build
import com.music.yzmusic.data.YtMusicRepository
import com.music.yzmusic.data.model.ShelfItem
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.LibraryViewType
import com.music.yzmusic.ui.screens.RecentShelfLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home screen's Recents shelf: which of its two layouts is in use, that the
 * choice outlives the process, and that neither laying the shelf out nor
 * switching between the two can rearrange or drop what is in it.
 */
class RecentShelfViewTypeTest {

    // ---- which layout, and does the choice survive a restart ----

    @Test
    fun `an account with no stored choice reads the recents shelf as a list`() {
        AppSettings.initForTest(FakeSharedPreferences(), sdkInt = Build.VERSION_CODES.S)

        assertEquals(
            "A reader who has never opened the toggle should get the list layout",
            LibraryViewType.LIST, AppSettings.homeRecentsViewType.value
        )
    }

    @Test
    fun `toggling a list shelf switches it to grid and writes that choice down`() {
        val prefs = FakeSharedPreferences()
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        AppSettings.toggleHomeRecentsViewType()

        assertEquals(
            "The first tap should offer the grid layout",
            LibraryViewType.GRID, AppSettings.homeRecentsViewType.value
        )
        assertEquals(
            "The choice has to reach disk, or it is lost on the next launch",
            "GRID", prefs.getString(KEY, null)
        )
    }

    @Test
    fun `toggling a grid shelf switches it back to a list and writes that choice down`() {
        val prefs = FakeSharedPreferences().apply { putString(KEY, "GRID") }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        AppSettings.toggleHomeRecentsViewType()

        assertEquals(
            "The tap after GRID should offer the list layout again",
            LibraryViewType.LIST, AppSettings.homeRecentsViewType.value
        )
        assertEquals("Going back to a list should be written down too", "LIST", prefs.getString(KEY, null))
    }

    @Test
    fun `a grid shelf is still a grid the next time the app starts`() {
        val prefs = FakeSharedPreferences()
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)
        AppSettings.toggleHomeRecentsViewType()

        // Re-reading the same store is what a relaunch amounts to.
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        assertEquals(
            "A reader who left on the grid layout should come back to it",
            LibraryViewType.GRID, AppSettings.homeRecentsViewType.value
        )
    }

    @Test
    fun `a list shelf is still a list the next time the app starts`() {
        val prefs = FakeSharedPreferences()
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        assertEquals(
            "Defaulting is not the same as forgetting: a list shelf stays a list",
            LibraryViewType.LIST, AppSettings.homeRecentsViewType.value
        )
    }

    @Test
    fun `a stored layout that is neither list nor grid reads as a list`() {
        val prefs = FakeSharedPreferences().apply { putString(KEY, "CAROUSEL") }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        assertEquals(
            "An unrecognised stored value should fall back to the list layout rather than throw",
            LibraryViewType.LIST, AppSettings.homeRecentsViewType.value
        )
    }

    // ---- the toggle belongs to Recents and to nothing else ----

    @Test
    fun `the recents shelf is the one shelf the layout toggle claims`() {
        assertTrue(
            "The shelf the repository titles for history must be the one that gets the toggle",
            RecentShelfLayout.isRecents(YtMusicRepository.RECENT_TITLE)
        )

        val otherShelves = listOf(
            "New releases",
            "Listen again",
            "Mood and moments",
            "Charts",
            "Videos",
            "Listen Now",
            // Near miss on purpose: shares the words without being the shelf.
            "Recently played albums",
            "",
        )
        for (title in otherShelves) {
            assertFalse(
                "'$title' is not the recents shelf and must keep the layout it already had",
                RecentShelfLayout.isRecents(title)
            )
        }
    }

    @Test
    fun `the recents shelf is recognised however its title is cased`() {
        assertTrue(
            "A feed that cased the title differently is still the recents shelf",
            RecentShelfLayout.isRecents(YtMusicRepository.RECENT_TITLE.lowercase())
        )
    }

    @Test
    fun `switching the recents shelf leaves the other shelves' stored layouts alone`() {
        val prefs = FakeSharedPreferences().apply { putString("library_view_type", "GRID") }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        AppSettings.toggleHomeRecentsViewType()

        assertEquals(
            "The recents toggle must not carry the Library layout with it",
            LibraryViewType.GRID, AppSettings.libraryViewType.value
        )
        assertEquals(
            "and must not rewrite the Library preference either",
            "GRID", prefs.getString("library_view_type", null)
        )
        assertEquals(
            "Recents keeps its own choice",
            "GRID", prefs.getString(KEY, null)
        )
    }

    // ---- laying the shelf out changes where a track sits, never which ----

    @Test
    fun `the list layout shows every track in the order the shelf arrived in`() {
        val items = tracks(20)

        val shown = RecentShelfLayout.columns(items).flatten()

        assertEquals(
            "The list layout must not drop a track, however it gathers them into columns",
            items.size, shown.size
        )
        assertEquals(
            "The list layout must keep the shelf's own order: recents is newest first",
            items.map { it.title }, shown.map { it.title }
        )
    }

    @Test
    fun `the list layout hands the rows the very tracks the shelf holds`() {
        val items = tracks(20)

        val shown = RecentShelfLayout.columns(items).flatten()

        // Identity, not equality: what a tap resolves to is the item the row was
        // drawn from, so a layout that rebuilt its tracks would play the wrong
        // one while still passing any comparison on values.
        items.forEachIndexed { index, item ->
            assertSame(
                "Track ${index + 1} should reach its row as itself, for tap and hold alike",
                item, shown[index]
            )
        }
    }

    @Test
    fun `the list layout gathers four tracks to a column`() {
        val columns = RecentShelfLayout.columns(tracks(20))

        assertEquals("Twenty tracks is five columns of four", 5, columns.size)
        assertTrue(
            "Every full column should hold four tracks",
            columns.all { it.size == 4 }
        )
    }

    @Test
    fun `a shelf that does not fill a column is left short rather than padded`() {
        val columns = RecentShelfLayout.columns(tracks(6))

        assertEquals("Six tracks is one full column and the remainder", 2, columns.size)
        assertEquals(4, columns[0].size)
        assertEquals(
            "The last column holds only what was left over — padding it would invent tracks",
            2, columns[1].size
        )
    }

    @Test
    fun `a shelf with nothing on it lays out as nothing at all`() {
        assertTrue(
            "An empty recents shelf should produce no columns rather than one empty one",
            RecentShelfLayout.columns(emptyList()).isEmpty()
        )
    }

    private fun tracks(count: Int): List<ShelfItem> = (1..count).map {
        ShelfItem(title = "Track $it", subtitle = "Artist $it", videoId = "video-$it")
    }

    private companion object {
        /** The preference key, written out rather than imported, to pin it on disk. */
        const val KEY = "home_recents_view_type"
    }
}
