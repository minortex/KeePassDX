package com.kunzisoft.keepass.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSyncFileNamesTest {

    @Test
    fun nameIsStableForTheSameUrl() {
        val url = "https://example.com/dav/passwords.kdbx"

        assertEquals(WebSyncFileNames.buildFileName(url), WebSyncFileNames.buildFileName(url))
    }

    @Test
    fun nameKeepsRemoteNameBehindAStableHexPrefix() {
        val name = WebSyncFileNames.buildFileName("https://example.com/dav/passwords.kdbx")

        assertTrue(name.endsWith("-passwords.kdbx"))
        val prefix = name.substringBefore('-')
        assertEquals(16, prefix.length)
        assertTrue(prefix.all { it in "0123456789abcdef" })
    }

    @Test
    fun differentUrlsProduceDifferentNames() {
        assertNotEquals(
            WebSyncFileNames.buildFileName("https://example.com/a.kdbx"),
            WebSyncFileNames.buildFileName("https://example.com/b.kdbx")
        )
    }

    @Test
    fun unsupportedCharactersInRemoteNameAreReplaced() {
        val name = WebSyncFileNames.buildFileName("https://example.com/dav/my vault.kdbx")

        assertTrue(name.endsWith("-my_vault.kdbx"))
    }

    @Test
    fun urlWithoutRemoteNameFallsBackToDefaultFileName() {
        val name = WebSyncFileNames.buildFileName("https://example.com/")

        assertTrue(name.endsWith("-websync_database.kdbx"))
    }
}
