package com.kunzisoft.keepass.database.action

import com.kunzisoft.keepass.database.exception.WebDavAuthenticationDatabaseException
import com.kunzisoft.keepass.database.exception.WebDavConflictDatabaseException
import com.kunzisoft.keepass.database.exception.WebDavDownloadDatabaseException
import com.kunzisoft.keepass.database.exception.WebDavUploadDatabaseException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavClientTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var sourceFile: File
    private lateinit var targetFile: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sourceFile = temporaryFolder.newFile("source.bin").apply {
            writeText("upload payload")
        }
        targetFile = temporaryFolder.newFile("target.bin")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun download200WritesFileAndReturnsMetadata() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("downloaded body")
                .addHeader("ETag", "\"etag-1\"")
                .addHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
        )

        val metadata = client().downloadToFile(targetFile)

        assertEquals("downloaded body", targetFile.readText())
        assertEquals("\"etag-1\"", metadata.eTag)
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", metadata.lastModified)
    }

    @Test
    fun download401And403ThrowAuthenticationException() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))

        assertThrows(WebDavAuthenticationDatabaseException::class.java) {
            client().downloadToFile(targetFile)
        }
        assertThrows(WebDavAuthenticationDatabaseException::class.java) {
            client().downloadToFile(targetFile)
        }
    }

    @Test
    fun download404ThrowsDownloadException() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertThrows(WebDavDownloadDatabaseException::class.java) {
            client().downloadToFile(targetFile)
        }
    }

    @Test
    fun requestUsesBasicAuthorizationHeader() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        client().downloadToFile(targetFile)

        val request = server.takeRequest()
        assertEquals("Basic dXNlcjpwYXNz", request.getHeader("Authorization"))
    }

    @Test
    fun uploadWithETagSendsIfMatch() {
        server.enqueue(MockResponse().setResponseCode(200))

        client().uploadFile(
            sourceFile,
            WebDavClient.RemoteFileMetadata(eTag = "\"etag-1\"", lastModified = null)
        )

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("\"etag-1\"", request.getHeader("If-Match"))
        assertNull(request.getHeader("If-Unmodified-Since"))
    }

    @Test
    fun uploadWithoutETagFallsBackToIfUnmodifiedSince() {
        val lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"
        server.enqueue(MockResponse().setResponseCode(200))

        client().uploadFile(
            sourceFile,
            WebDavClient.RemoteFileMetadata(eTag = null, lastModified = lastModified)
        )

        val request = server.takeRequest()
        assertNull(request.getHeader("If-Match"))
        assertEquals(lastModified, request.getHeader("If-Unmodified-Since"))
    }

    @Test
    fun uploadWithoutValidatorThrowsConflictAndDoesNotSendRequest() {
        assertThrows(WebDavConflictDatabaseException::class.java) {
            client().uploadFile(
                sourceFile,
                WebDavClient.RemoteFileMetadata(eTag = null, lastModified = null)
            )
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun upload412ThrowsConflictException() {
        server.enqueue(MockResponse().setResponseCode(412))

        assertThrows(WebDavConflictDatabaseException::class.java) {
            client().uploadFile(
                sourceFile,
                WebDavClient.RemoteFileMetadata(eTag = "\"etag-1\"", lastModified = null)
            )
        }
    }

    @Test
    fun downloadConnectionFailureThrowsDownloadException() {
        val deadServer = MockWebServer().apply { start() }
        val deadUrl = deadServer.url("/database.kdbx").toString()
        deadServer.shutdown()

        assertThrows(WebDavDownloadDatabaseException::class.java) {
            WebDavClient(deadUrl, "user", "pass").downloadToFile(targetFile)
        }
    }

    @Test
    fun upload401And403ThrowAuthenticationException() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))
        val metadata = WebDavClient.RemoteFileMetadata(eTag = "\"etag-1\"", lastModified = null)

        assertThrows(WebDavAuthenticationDatabaseException::class.java) {
            client().uploadFile(sourceFile, metadata)
        }
        assertThrows(WebDavAuthenticationDatabaseException::class.java) {
            client().uploadFile(sourceFile, metadata)
        }
    }

    @Test
    fun uploadConnectionFailureThrowsUploadException() {
        val deadServer = MockWebServer().apply { start() }
        val deadUrl = deadServer.url("/database.kdbx").toString()
        deadServer.shutdown()

        assertThrows(WebDavUploadDatabaseException::class.java) {
            WebDavClient(deadUrl, "user", "pass").uploadFile(
                sourceFile,
                WebDavClient.RemoteFileMetadata(eTag = "\"etag-1\"", lastModified = null)
            )
        }
    }

    private fun client(
        url: String = server.url("/database.kdbx").toString()
    ) = WebDavClient(url, "user", "pass")
}
