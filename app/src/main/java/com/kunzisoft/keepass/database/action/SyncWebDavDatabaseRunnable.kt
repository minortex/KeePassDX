package com.kunzisoft.keepass.database.action

import android.os.Bundle
import android.content.Context
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.Entry
import com.kunzisoft.keepass.database.element.Group
import com.kunzisoft.keepass.database.element.binary.BinaryData
import com.kunzisoft.keepass.database.exception.DatabaseException
import com.kunzisoft.keepass.database.exception.RegisterInReadOnlyDatabaseException
import com.kunzisoft.keepass.database.exception.UnknownDatabaseLocationException
import com.kunzisoft.keepass.database.exception.WebDavConfigurationDatabaseException
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ProgressTaskUpdater
import java.io.File

class SyncWebDavDatabaseRunnable(
    context: Context,
    private val challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
    private val progressTaskUpdater: ProgressTaskUpdater?,
    database: ContextualDatabase,
    saveDatabase: Boolean,
    private val uploadToWebDav: Boolean,
) : SaveDatabaseRunnable(
    context = context,
    database = database,
    saveDatabase = saveDatabase,
    mainCredential = null,
    challengeResponseRetriever = challengeResponseRetriever
) {
    private val saveToLocal = saveDatabase

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
            val canAllocateInRam = { memoryWanted: Long ->
                BinaryData.canMemoryBeAllocatedInRAM(context, memoryWanted)
            }

            fileToMerge = File.createTempFile("webdav_download_", ".kdbx", context.cacheDir)
            val downloadedETag = webDavClient.downloadToFile(fileToMerge)

            fileToMerge.inputStream().use { databaseToMergeInputStream ->
                database.mergeData(
                    databaseToMergeInputStream,
                    null,
                    challengeResponseRetriever,
                    canAllocateInRam,
                    progressTaskUpdater
                )
            }
            val changeSummary = computeChangeSummary(beforeSnapshots, buildEntrySnapshots())

            super.onActionRun()
            if (result.isSuccess) {
                val bundle = result.data ?: Bundle()
                bundle.putInt(RESULT_SYNC_ADDED_COUNT, changeSummary.added)
                bundle.putInt(RESULT_SYNC_DELETED_COUNT, changeSummary.deleted)
                bundle.putInt(RESULT_SYNC_MODIFIED_COUNT, changeSummary.modified)
                result.data = bundle
            }

            if (result.isSuccess && uploadToWebDav) {
                fileToUpload = File.createTempFile("webdav_upload_", ".kdbx", context.cacheDir)
                exportMergedDatabase(fileToUpload)
                webDavClient.uploadFile(fileToUpload, downloadedETag)
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
        database.saveData(
            cacheFile = cacheFile,
            databaseOutputStream = { outputFile.outputStream() },
            isNewLocation = false,
            masterCredential = null,
            challengeResponseRetriever = challengeResponseRetriever
        )
    }

    private fun buildEntrySnapshots(): Map<String, EntrySnapshot> {
        val snapshots = mutableMapOf<String, EntrySnapshot>()
        fun traverse(group: Group) {
            group.getChildEntries().forEach { entry ->
                val entryId = entry.nodeId.toString()
                val parentId = entry.parent?.nodeId?.toString().orEmpty()
                val snapshot = EntrySnapshot(
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

    private fun computeChangeSummary(
        before: Map<String, EntrySnapshot>,
        after: Map<String, EntrySnapshot>
    ): ChangeSummary {
        var added = 0
        var deleted = 0
        var modified = 0

        val allIds = mutableSetOf<String>()
        allIds.addAll(before.keys)
        allIds.addAll(after.keys)

        allIds.forEach { entryId ->
            val beforeSnapshot = before[entryId]
            val afterSnapshot = after[entryId]
            when {
                beforeSnapshot == null && afterSnapshot != null -> {
                    if (!afterSnapshot.inRecycleBin) {
                        added++
                    }
                }
                beforeSnapshot != null && afterSnapshot == null -> {
                    if (!beforeSnapshot.inRecycleBin) {
                        deleted++
                    }
                }
                beforeSnapshot != null && afterSnapshot != null -> {
                    if (!beforeSnapshot.inRecycleBin && afterSnapshot.inRecycleBin) {
                        deleted++
                    } else if (beforeSnapshot.inRecycleBin && !afterSnapshot.inRecycleBin) {
                        added++
                    } else if (beforeSnapshot.fingerprint != afterSnapshot.fingerprint) {
                        modified++
                    }
                }
            }
        }
        return ChangeSummary(added, deleted, modified)
    }

    private data class EntrySnapshot(
        val fingerprint: String,
        val inRecycleBin: Boolean
    )

    private data class ChangeSummary(
        val added: Int,
        val deleted: Int,
        val modified: Int
    )

    companion object {
        const val RESULT_SYNC_ADDED_COUNT = "RESULT_SYNC_ADDED_COUNT"
        const val RESULT_SYNC_DELETED_COUNT = "RESULT_SYNC_DELETED_COUNT"
        const val RESULT_SYNC_MODIFIED_COUNT = "RESULT_SYNC_MODIFIED_COUNT"
    }
}
