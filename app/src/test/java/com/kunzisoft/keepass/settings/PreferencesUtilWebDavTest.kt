package com.kunzisoft.keepass.settings

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesUtilWebDavTest {

    private lateinit var context: Context

    private val databaseUri = Uri.parse("content://com.kunzisoft.keepass.test/database.kdbx")
    private val otherDatabaseUri = Uri.parse("content://com.kunzisoft.keepass.test/other.kdbx")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        clearPreferences()
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun webDavCredentialsRoundTrip() {
        PreferencesUtil.setWebDavUrl(context, databaseUri, "https://example.com/webdav")
        PreferencesUtil.setWebDavUsername(context, databaseUri, "alice")
        PreferencesUtil.setWebDavPassword(context, databaseUri, "secret")

        assertEquals("https://example.com/webdav", PreferencesUtil.getWebDavUrl(context, databaseUri))
        assertEquals("alice", PreferencesUtil.getWebDavUsername(context, databaseUri))
        assertEquals("secret", PreferencesUtil.getWebDavPassword(context, databaseUri))
    }

    @Test
    fun isWebDavSyncConfiguredRequiresUrlUsernameAndPassword() {
        assertFalse(PreferencesUtil.isWebDavSyncConfigured(context, databaseUri))

        PreferencesUtil.setWebDavUrl(context, databaseUri, "https://example.com/webdav")
        PreferencesUtil.setWebDavUsername(context, databaseUri, "alice")
        PreferencesUtil.setWebDavPassword(context, databaseUri, "secret")
        assertTrue(PreferencesUtil.isWebDavSyncConfigured(context, databaseUri))

        PreferencesUtil.setWebDavUrl(context, databaseUri, "")
        assertFalse(PreferencesUtil.isWebDavSyncConfigured(context, databaseUri))
        PreferencesUtil.setWebDavUrl(context, databaseUri, "   ")
        assertFalse(PreferencesUtil.isWebDavSyncConfigured(context, databaseUri))

        PreferencesUtil.setWebDavUrl(context, databaseUri, "https://example.com/webdav")
        PreferencesUtil.setWebDavUsername(context, databaseUri, "")
        assertFalse(PreferencesUtil.isWebDavSyncConfigured(context, databaseUri))
        PreferencesUtil.setWebDavUsername(context, databaseUri, "   ")
        assertFalse(PreferencesUtil.isWebDavSyncConfigured(context, databaseUri))

        PreferencesUtil.setWebDavUsername(context, databaseUri, "alice")
        PreferencesUtil.setWebDavPassword(context, databaseUri, "")
        assertFalse(PreferencesUtil.isWebDavSyncConfigured(context, databaseUri))
    }

    @Test
    fun webDavCredentialsAreScopedByDatabaseUri() {
        PreferencesUtil.setWebDavUrl(context, databaseUri, "https://example.com/first")
        PreferencesUtil.setWebDavUsername(context, databaseUri, "first-user")
        PreferencesUtil.setWebDavPassword(context, databaseUri, "first-password")

        PreferencesUtil.setWebDavUrl(context, otherDatabaseUri, "https://example.com/second")
        PreferencesUtil.setWebDavUsername(context, otherDatabaseUri, "second-user")
        PreferencesUtil.setWebDavPassword(context, otherDatabaseUri, "second-password")

        assertEquals("https://example.com/first", PreferencesUtil.getWebDavUrl(context, databaseUri))
        assertEquals("first-user", PreferencesUtil.getWebDavUsername(context, databaseUri))
        assertEquals("first-password", PreferencesUtil.getWebDavPassword(context, databaseUri))

        assertEquals("https://example.com/second", PreferencesUtil.getWebDavUrl(context, otherDatabaseUri))
        assertEquals("second-user", PreferencesUtil.getWebDavUsername(context, otherDatabaseUri))
        assertEquals("second-password", PreferencesUtil.getWebDavPassword(context, otherDatabaseUri))
    }

    @Test
    fun webDavSyncOnOpenDefaultsTrueAndIsScopedByDatabaseUri() {
        assertTrue(PreferencesUtil.isWebDavSyncOnOpenEnabled(context, databaseUri))
        assertTrue(PreferencesUtil.isWebDavSyncOnOpenEnabled(context, otherDatabaseUri))

        PreferencesUtil.setWebDavSyncOnOpenEnabled(context, databaseUri, false)

        assertFalse(PreferencesUtil.isWebDavSyncOnOpenEnabled(context, databaseUri))
        assertTrue(PreferencesUtil.isWebDavSyncOnOpenEnabled(context, otherDatabaseUri))

        PreferencesUtil.setWebDavSyncOnOpenEnabled(context, otherDatabaseUri, false)

        assertFalse(PreferencesUtil.isWebDavSyncOnOpenEnabled(context, databaseUri))
        assertFalse(PreferencesUtil.isWebDavSyncOnOpenEnabled(context, otherDatabaseUri))
    }

    private fun clearPreferences() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .clear()
            .commit()
    }
}
