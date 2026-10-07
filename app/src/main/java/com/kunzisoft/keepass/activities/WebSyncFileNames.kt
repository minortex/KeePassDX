package com.kunzisoft.keepass.activities

import android.net.Uri
import java.security.MessageDigest

/**
 * Deterministic local file naming for databases opened through WebSync.
 *
 * The generated name must stay stable for a given remote URL: the local file URI built from it is
 * the key used to store the WebDAV credentials, so an unstable name would orphan the configuration.
 */
internal object WebSyncFileNames {

    private const val DEFAULT_FILE_NAME = "websync_database.kdbx"

    fun buildFileName(url: String): String {
        return "${buildStablePrefix(url)}-${buildRemoteFileName(url)}"
    }

    fun buildStablePrefix(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun buildRemoteFileName(url: String): String {
        val remoteName = runCatching { Uri.parse(url).lastPathSegment.orEmpty() }
            .getOrDefault("")
        return remoteName
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_', '.')
            .ifBlank { DEFAULT_FILE_NAME }
    }
}
