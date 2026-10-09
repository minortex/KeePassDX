package com.kunzisoft.keepass.database.action

import android.os.Bundle
import android.content.Context
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.Entry
import com.kunzisoft.keepass.database.element.Group
import com.kunzisoft.keepass.database.exception.DatabaseException
import com.kunzisoft.keepass.database.exception.RegisterInReadOnlyDatabaseException
import com.kunzisoft.keepass.database.exception.UnknownDatabaseLocationException
import com.kunzisoft.keepass.database.exception.WebDavConfigurationDatabaseException
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ProgressTaskUpdater
import com.kunzisoft.keepass.utils.AppUtil.getLimits
import java.io.File

class SyncWebDavDatabaseRunnable(
    context: Context,
    challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
    progressTaskUpdater: ProgressTaskUpdater?,
    database: ContextualDatabase,
    save: Boolean,
    private val uploadToWebDav: Boolean,
) : SaveDatabaseRunnable(
    context = context,
    database = database,
    save = save,
    mainCredential = null,
    challengeResponseRetriever = challengeResponseRetriever,
    progressTaskUpdater = progressTaskUpdater
) {
    private val saveToLocal = save

    override fun onStartRun() {
        database.wasReloaded = true
        super.onStartRun()
    }

    override fun onActionRun() {
        var fileToMerge: File? = null
        var fileToUpload: File? = null
        try {
            val databaseUri = database.fileUri ?: throw UnknownDatabaseLocationException()
            if (!saveToLocal) {
                throw RegisterInReadOnlyDatabaseException()
            }
            if (!PreferencesUtil.isWebDavSyncConfigured(context, databaseUri)) {
                throw WebDavConfigurationDatabaseException()
            }

            val webDavClient = WebDavClient(
                url = PreferencesUtil.getWebDavUrl(context, databaseUri).trim(),
                username = PreferencesUtil.getWebDavUsername(context, databaseUri).trim(),
                password = PreferencesUtil.getWebDavPassword(context, databaseUri)
            )
            val beforeSnapshots = buildEntrySnapshots()

            fileToMerge = File.createTempFile("webdav_download_", ".kdbx", context.cacheDir)
            val remoteFileMetadata = webDavClient.downloadToFile(fileToMerge)

            fileToMerge.inputStream().use { databaseToMergeInputStream ->
                database.mergeData(
                    databaseToMergeStream = databaseToMergeInputStream,
                    databaseToMergeMasterCredential = null,
                    databaseToMergeChallengeResponseRetriever = challengeResponseRetriever,
                    limits = context.getLimits(),
                    progressTaskUpdater = progressTaskUpdater
                )
            }
            val changeSummary = SyncChangeSummary.compute(beforeSnapshots, buildEntrySnapshots())

            super.onActionRun()

            if (result.isSuccess && uploadToWebDav) {
                fileToUpload = File.createTempFile("webdav_upload_", ".kdbx", context.cacheDir)
                exportMergedDatabase(fileToUpload)
                webDavClient.uploadFile(fileToUpload, remoteFileMetadata)
            }

            if (result.isSuccess) {
                val bundle = result.data ?: Bundle()
                bundle.putInt(RESULT_SYNC_ADDED_COUNT, changeSummary.added)
                bundle.putInt(RESULT_SYNC_DELETED_COUNT, changeSummary.deleted)
                bundle.putInt(RESULT_SYNC_MODIFIED_COUNT, changeSummary.modified)
                result.data = bundle
            }
        } catch (e: DatabaseException) {
            setError(e)
        } finally {
            fileToMerge?.delete()
            fileToUpload?.delete()
        }
    }

    private fun exportMergedDatabase(outputFile: File) {
        val cacheFile = File.createTempFile("webdav_upload_cache_", ".kdbx", context.cacheDir)
        try {
            database.saveData(
                cacheFile = cacheFile,
                databaseOutputStream = { outputFile.outputStream() },
                masterCredential = null,
                challengeResponseRetriever = challengeResponseRetriever,
                limits = context.getLimits()
            )
        } finally {
            cacheFile.delete()
        }
    }

    private fun buildEntrySnapshots(): Map<String, SyncChangeSummary.Snapshot> {
        val snapshots = mutableMapOf<String, SyncChangeSummary.Snapshot>()
        fun traverse(group: Group) {
            group.getChildEntries().forEach { entry ->
                val entryId = entry.nodeId.toString()
                val parentId = entry.parent?.nodeId?.toString().orEmpty()
                val snapshot = SyncChangeSummary.Snapshot(
                    fingerprint = "${entry.lastModificationTime.toMilliseconds()}#$parentId",
                    inRecycleBin = isEntryInRecycleBin(entry)
                )
                snapshots[entryId] = snapshot
            }
            group.getChildGroups().forEach(::traverse)
        }
        database.rootGroup?.let(::traverse)
        return snapshots
    }

    private fun isEntryInRecycleBin(entry: Entry): Boolean {
        val parent = entry.parent ?: return false
        return database.groupIsInRecycleBin(parent)
    }

    companion object {
        const val RESULT_SYNC_ADDED_COUNT = "RESULT_SYNC_ADDED_COUNT"
        const val RESULT_SYNC_DELETED_COUNT = "RESULT_SYNC_DELETED_COUNT"
        const val RESULT_SYNC_MODIFIED_COUNT = "RESULT_SYNC_MODIFIED_COUNT"
    }
}
