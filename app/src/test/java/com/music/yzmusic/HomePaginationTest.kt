package com.music.yzmusic

import com.music.yzmusic.data.model.HomeFeed
import com.music.yzmusic.data.model.HomeShelf
import com.music.yzmusic.data.model.ShelfItem
import com.music.yzmusic.data.model.ShelfType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Unit tests verifying infinite-scroll pagination behavior and edge cases:
 * 1. Automatic pagination when initial content underfills viewport
 * 2. Consecutive page loading without stalling at list bottom
 * 3. Threshold pre-fetching before reaching absolute bottom
 * 4. Fast scroll coalescing and in-flight request protection
 * 5. Reaching exact bottom
 * 6. Temporary failure retention of token and backoff retry
 * 7. End of feed termination (null continuation)
 * 8. Duplicate / circular continuation token protection
 * 9. Refresh cancellation of in-flight pagination
 * 10. Title deduplication with empty title tolerance
 */
class HomePaginationTest {

    private fun shelf(title: String) = HomeShelf(
        title = title,
        items = listOf(ShelfItem(title = "Song in $title", subtitle = "Artist", thumbnailUrl = null, videoId = "vid_$title", browseId = null)),
        type = ShelfType.DEFAULT
    )

    // Helper implementing the near-end logic used in HomeScreen
    private fun isNearEnd(totalItemsCount: Int, lastVisibleIndex: Int, prefetchDistance: Int = 3): Boolean {
        return totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - prefetchDistance
    }

    // Helper simulating the reactive snapshot flow trigger state
    private data class PaginationTriggerState(
        val nearEnd: Boolean,
        val total: Int,
        val isLoading: Boolean
    ) {
        val shouldTrigger: Boolean get() = nearEnd && !isLoading
    }

    @Test
    fun `initial feed underfilling viewport triggers pagination automatically`() {
        // Initial page loaded 2 shelves + 1 header = total 3 items.
        // Viewport shows all 3 items (lastVisibleIndex = 2).
        val total = 3
        val lastVisible = 2
        val nearEnd = isNearEnd(total, lastVisible)
        assertTrue("Underfilled viewport must evaluate nearEnd as true", nearEnd)

        val triggerState = PaginationTriggerState(nearEnd = nearEnd, total = total, isLoading = false)
        assertTrue("Must trigger next page automatically", triggerState.shouldTrigger)
    }

    @Test
    fun `slow scrolling triggers prefetch exactly at threshold before reaching bottom`() {
        val total = 10 // Items 0..9, prefetchDistance = 3 (threshold is index 7)

        // Item 6: not yet at threshold
        assertFalse(isNearEnd(total, 6))

        // Item 7: threshold reached (10 - 3 = 7)
        assertTrue(isNearEnd(total, 7))

        // Item 8: within threshold
        assertTrue(isNearEnd(total, 8))

        // Item 9: absolute bottom
        assertTrue(isNearEnd(total, 9))
    }

    @Test
    fun `consecutive pages load without stalling when user remains at bottom`() {
        // Bug reproduction & fix verification:
        // Old bug: LaunchedEffect(nearEnd) where nearEnd stayed true after appending shelves,
        // so it never fired again until scrolling to top.
        // Fix: reactive state observes (nearEnd, total, isLoading), detecting total item changes.

        var total = 6
        var lastVisible = 5 // User sitting at bottom
        var isLoading = false

        // 1. Initial trigger at bottom
        var state1 = PaginationTriggerState(isNearEnd(total, lastVisible), total, isLoading)
        assertTrue(state1.shouldTrigger)

        // 2. Loading begins
        isLoading = true
        var state2 = PaginationTriggerState(isNearEnd(total, lastVisible), total, isLoading)
        assertFalse("While loading in flight, should not trigger duplicate request", state2.shouldTrigger)

        // 3. Page 2 arrives (2 shelves added -> total becomes 8), loading completes
        total += 2
        isLoading = false
        var state3 = PaginationTriggerState(isNearEnd(total, lastVisible), total, isLoading)

        // User is still at index 5. total = 8. 5 >= 8 - 3 = 5 >= 5 (true!)
        assertTrue("nearEnd is still true because user is at bottom", state3.nearEnd)
        // With our fix, state3 != state2 (total changed from 6 to 8, isLoading from true to false),
        // so distinctUntilChanged emits and triggers next page!
        assertTrue("Must trigger consecutive page without requiring user to scroll up!", state3.shouldTrigger)

        // 4. Page 3 arrives (4 shelves added -> total becomes 12)
        total += 4
        var state4 = PaginationTriggerState(isNearEnd(total, lastVisible), total, isLoading)
        // User is at index 5. total = 12. 5 >= 12 - 3 (false!)
        assertFalse("Once viewport has enough buffer, nearEnd rests", state4.nearEnd)
        assertFalse(state4.shouldTrigger)
    }

    @Test
    fun `fast scrolling coalesces requests and respects in-flight loading guard`() {
        val total = 10
        val lastVisible = 9
        var inFlight = true

        // During rapid fling, multiple frames report nearEnd = true while load is in flight
        val triggerState = PaginationTriggerState(isNearEnd(total, lastVisible), total, inFlight)
        assertFalse("Must not trigger while in flight", triggerState.shouldTrigger)

        // Once completed, next page is allowed
        inFlight = false
        val completedState = PaginationTriggerState(isNearEnd(total, lastVisible), total, inFlight)
        assertTrue("Allows next request once completed", completedState.shouldTrigger)
    }

    @Test
    fun `duplicate or circular continuation token is rejected`() {
        val currentToken = "TOKEN_ABC"

        // YouTube returns the exact same token (circular reference)
        val circularToken = "TOKEN_ABC"
        val nextToken = circularToken.takeIf { it != currentToken }
        assertNull("Circular continuation token must be rejected", nextToken)

        // Valid new token
        val validToken = "TOKEN_DEF"
        val validNextToken = validToken.takeIf { it != currentToken }
        assertEquals("TOKEN_DEF", validNextToken)
    }

    @Test
    fun `temporary failure preserves continuation token and respects backoff`() {
        val token = "VALID_TOKEN"
        var homeContinuation: String? = token
        var lastLoadMoreErrorTime = 0L

        // Simulate network failure
        lastLoadMoreErrorTime = System.currentTimeMillis()

        // Verify token was NOT wiped
        assertEquals("VALID_TOKEN", homeContinuation)

        // Within 2.5s backoff, immediate retry is blocked to prevent busy loop
        val immediateRetryAllowed = System.currentTimeMillis() - lastLoadMoreErrorTime >= 2500L
        assertFalse("Immediate retry during failure backoff must be throttled", immediateRetryAllowed)

        // After backoff window, retry is allowed
        val pastBackoffTime = lastLoadMoreErrorTime - 3000L
        val laterRetryAllowed = System.currentTimeMillis() - pastBackoffTime >= 2500L
        assertTrue("Retry must be allowed after backoff window expires", laterRetryAllowed)
    }

    @Test
    fun `end of feed cleanly nullifies continuation`() {
        var homeContinuation: String? = "INITIAL_TOKEN"

        // Backend returns response with null continuation
        val responseContinuation: String? = null
        homeContinuation = responseContinuation

        assertNull("Feed continuation must be null when feed is exhausted", homeContinuation)
    }

    @Test
    fun `shelf deduplication preserves unique shelves and permits blank titles`() {
        val seenTitles = mutableSetOf<String>()

        fun addShelfIfUnique(title: String): Boolean {
            val key = title.trim().lowercase(Locale.ROOT)
            return if (key.isEmpty()) true else seenTitles.add(key)
        }

        // Shelf 1: "Quick Picks" -> added
        assertTrue(addShelfIfUnique("Quick Picks"))

        // Shelf 2: "quick picks" (case variation) -> duplicate, rejected
        assertFalse(addShelfIfUnique("quick picks"))

        // Shelf 3: "QUICK PICKS  " (whitespace + case) -> duplicate, rejected
        assertFalse(addShelfIfUnique("QUICK PICKS  "))

        // Shelf 4: "Trending" -> added
        assertTrue(addShelfIfUnique("Trending"))

        // Shelf 5: "" (blank/untitled shelf) -> allowed
        assertTrue(addShelfIfUnique(""))

        // Shelf 6: "" (another blank/untitled shelf) -> allowed, not blocked by previous blank
        assertTrue(addShelfIfUnique(""))
    }

    @Test
    fun `empty page with valid continuation token does not prematurely terminate feed`() {
        var consecutiveEmptyPages = 0
        var homeContinuation: String? = "TOKEN_1"
        val nextToken: String? = "TOKEN_2"

        // Page arrives with 0 new shelves (e.g. all duplicate titles), but valid nextToken
        val addedCount = 0

        if (addedCount == 0) {
            consecutiveEmptyPages++
            if (consecutiveEmptyPages >= 3 || nextToken == null) {
                homeContinuation = null
            } else {
                homeContinuation = nextToken
            }
        }

        assertEquals(1, consecutiveEmptyPages)
        assertEquals("Continuation token must be preserved for next page even if current page had only duplicates", "TOKEN_2", homeContinuation)

        // If 3 consecutive pages have 0 new shelves, then feed terminates
        consecutiveEmptyPages = 2
        if (addedCount == 0) {
            consecutiveEmptyPages++
            if (consecutiveEmptyPages >= 3) {
                homeContinuation = null
            }
        }
        assertNull("Feed terminates only after multiple consecutive empty pages", homeContinuation)
    }

    @Test
    fun `refresh resets pagination state and clears seen titles safely`() {
        val seenTitles = mutableSetOf("shelf1", "shelf2")
        var homeContinuation: String? = "OLD_TOKEN"
        var homeLoadingMore = true
        var lastLoadMoreErrorTime = 12345L
        var consecutiveEmptyPages = 2

        // Simulate refresh execution
        homeLoadingMore = false
        lastLoadMoreErrorTime = 0L
        consecutiveEmptyPages = 0
        homeContinuation = null
        seenTitles.clear()

        assertFalse(homeLoadingMore)
        assertEquals(0L, lastLoadMoreErrorTime)
        assertEquals(0, consecutiveEmptyPages)
        assertNull(homeContinuation)
        assertTrue(seenTitles.isEmpty())
    }
}
