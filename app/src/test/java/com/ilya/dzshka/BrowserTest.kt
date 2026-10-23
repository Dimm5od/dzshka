package com.ilya.dzshka

import com.ilya.dzshka.ui.BrowserInput
import com.ilya.dzshka.ui.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserTest {
    private val g = SearchEngine.GOOGLE

    @Test fun addressesOpenAsSites() {
        assertEquals("https://ru.wikipedia.org", BrowserInput.toUrl("ru.wikipedia.org", g))
        assertEquals("https://example.com/a?b=1", BrowserInput.toUrl("https://example.com/a?b=1", g))
        assertEquals("https://школа.укр", BrowserInput.toUrl("школа.укр", g))
        assertEquals("https://mathway.com/ru/Algebra", BrowserInput.toUrl("  mathway.com/ru/Algebra ", g))
    }

    @Test fun wordsGoToSearch() {
        assertTrue(BrowserInput.toUrl("теорема Пифагора", g).startsWith("https://www.google.com/search?q="))
        assertTrue(BrowserInput.toUrl("фотосинтез", g).startsWith("https://www.google.com/search?q="))
        assertTrue(BrowserInput.toUrl("2+2", SearchEngine.WIKI).startsWith("https://ru.wikipedia.org/w/index.php?search="))
        assertEquals(g.home, BrowserInput.toUrl("   ", g))
    }
}
