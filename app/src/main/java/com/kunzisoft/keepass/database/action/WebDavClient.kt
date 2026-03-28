package com.kunzisoft.keepass.database.action

import android.util.Base64
import com.kunzisoft.keepass.database.exception.DatabaseException
import com.kunzisoft.keepass.database.exception.WebDavAuthenticationDatabaseException
import com.kunzisoft.keepass.database.exception.WebDavConflictDatabaseException
import com.kunzisoft.keepass.database.exception.WebDavDownloadDatabaseException
import com.kunzisoft.keepass.database.exception.WebDavUploadDatabaseException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WebDavClient(
    private val url: String,
    private val username: String,
    private val password: String
) {

    fun downloadToFile(targetFile: File): String? {
        val connection = buildConnection("GET")
        return try {
            when (val responseCode = connection.responseCode) {
                in 200..299 -> {
                    connection.inputStream.use { inputStream ->
                        targetFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    connection.getHeaderField("ETag")
                }
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN -> throw WebDavAuthenticationDatabaseException()
                else -> throw WebDavDownloadDatabaseException("HTTP $responseCode")
            }
        } catch (e: DatabaseException) {
            throw e
        } catch (e: Exception) {
            throw WebDavDownloadDatabaseException(e)
        } finally {
            connection.disconnect()
        }
    }

    fun uploadFile(sourceFile: File, eTag: String?) {
        val connection = buildConnection("PUT").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(sourceFile.length())
            if (!eTag.isNullOrEmpty()) {
                setRequestProperty("If-Match", eTag)
            }
        }
        try {
            sourceFile.inputStream().use { inputStream ->
                connection.outputStream.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            when (val responseCode = connection.responseCode) {
                in 200..299 -> Unit
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN -> throw WebDavAuthenticationDatabaseException()
                HttpURLConnection.HTTP_PRECON_FAILED -> throw WebDavConflictDatabaseException()
                else -> throw WebDavUploadDatabaseException("HTTP $responseCode")
            }
        } catch (e: DatabaseException) {
            throw e
        } catch (e: Exception) {
            throw WebDavUploadDatabaseException(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildConnection(method: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30000
            readTimeout = 30000
            setRequestProperty("Authorization", buildAuthorizationHeader())
        }
    }

    private fun buildAuthorizationHeader(): String {
        val auth = "$username:$password"
        val encodedAuth = Base64.encodeToString(auth.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encodedAuth"
    }
}
