package com.kunzisoft.keepass.database.action

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.exception.RegisterInReadOnlyDatabaseException
import com.kunzisoft.keepass.database.exception.UnknownDatabaseLocationException
import com.kunzisoft.keepass.database.exception.WebDavConfigurationDatabaseException
import com.kunzisoft.keepass.database.exception.WebDavDownloadDatabaseException
import com.kunzisoft.keepass.settings.PreferencesUtil
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards and error mapping of the WebDAV sync runnable.
 *
 * The download/merge/save steps use the native KDBX engine, which is not available in JVM unit
 * tests, so these cases stop before real decryption. The full round-trip is covered by
 * DatabaseMergeRoundTripTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWebDavDatabaseRunnableTest {

    private lateinit var context: Context

    private val databaseUri = Uri.parse("content://com.kunzisoft.keepass.test/database.kdbx")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPreferences()
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun runWithoutFileUriReportsUnknownDatabaseLocation() {
        val database = ContextualDatabase().apply { fileUri = null }

        val runnable = syncRunnable(database, save = true)
        runnable.run()

        assertTrue(runnable.result.exception is UnknownDatabaseLocationException)
    }

    @Test
    fun runOnReadOnlyDatabaseReportsRegisterInReadOnly() {
        val database = ContextualDatabase().apply { fileUri = databaseUri }

        val runnable = syncRunnable(database, save = false)
        runnable.run()

        assertTrue(runnable.result.exception is RegisterInReadOnlyDatabaseException)
    }

    @Test
    fun runWithoutWebDavConfigurationReportsConfigurationError() {
        val database = ContextualDatabase().apply { fileUri = databaseUri }

        val runnable = syncRunnable(database, save = true)
        runnable.run()

        assertTrue(runnable.result.exception is WebDavConfigurationDatabaseException)
    }

    @Test
    fun downloadFailureReportsDownloadErrorAndCleansTempFile() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            configureWebDav(server.url("/database.kdbx").toString())
            val database = ContextualDatabase().apply { fileUri = databaseUri }

            val runnable = syncRunnable(database, save = true)
            runnable.run()

            assertTrue(runnable.result.exception is WebDavDownloadDatabaseException)
            assertTrue(
                "Temp download file should be removed",
                context.cacheDir.listFiles().orEmpty()
                    .none { it.name.startsWith("webdav_download_") }
            )
        } finally {
            server.shutdown()
        }
    }

    private fun syncRunnable(
        database: ContextualDatabase,
        save: Boolean,
        uploadToWebDav: Boolean = true
    ): SyncWebDavDatabaseRunnable = SyncWebDavDatabaseRunnable(
        context = context,
        challengeResponseRetriever = { _, _ -> ByteArray(0) },
        progressTaskUpdater = null,
        database = database,
        save = save,
        uploadToWebDav = uploadToWebDav
    )

    private fun configureWebDav(url: String) {
        PreferencesUtil.setWebDavUrl(context, databaseUri, url)
        PreferencesUtil.setWebDavUsername(context, databaseUri, "user")
        PreferencesUtil.setWebDavPassword(context, databaseUri, "pass")
    }

    private fun clearPreferences() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .clear()
            .commit()
    }
}
