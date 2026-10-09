package com.kunzisoft.keepass.tests.database

import androidx.test.platform.app.InstrumentationRegistry
import com.kunzisoft.keepass.database.crypto.kdf.Limits
import com.kunzisoft.keepass.database.element.DateInstant
import com.kunzisoft.keepass.database.element.Database
import com.kunzisoft.keepass.database.element.MasterCredential
import com.kunzisoft.keepass.database.element.database.DatabaseKDBX
import com.kunzisoft.keepass.database.element.node.NodeIdUUID
import com.kunzisoft.keepass.database.file.output.DatabaseOutputKDBX
import com.kunzisoft.keepass.hardware.HardwareKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Instrumented round-trip test: save real KDBX files, reopen them and merge.
 *
 * This must run as an instrumented (androidTest) test because KDBX serialization uses the
 * native AES / Argon2 libraries, which are not available in plain JVM unit tests.
 */
class DatabaseMergeRoundTripTest {

    private val cacheDirectory: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun merge_roundTrip_throughKdbxSerialization_keepsBothSidesChanges() {
        val tempDir = File(cacheDirectory, "merge-roundtrip-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val databaseA = createDatabaseFromSameBase()
            val databaseB = createDatabaseFromSameBase()

            addEntry(databaseA, SHARED_GROUP_ID, ENTRY_A_ID, ENTRY_FROM_A)
            addEntry(databaseB, SHARED_GROUP_ID, ENTRY_B_ID, ENTRY_FROM_B)

            val fileA = File(tempDir, "database-a.kdbx")
            val fileB = File(tempDir, "database-b.kdbx")
            val masterCredential = MasterCredential(TEST_PASSWORD.toCharArray())

            saveDatabase(databaseA, fileA, masterCredential)
            saveDatabase(databaseB, fileB, masterCredential)

            val loadedA = loadDatabase(fileA, masterCredential, tempDir)
            val loadedB = loadDatabase(fileB, masterCredential, tempDir)
            assertNotNull("Reopened database A should contain its entry", loadedA.getEntryById(NodeIdUUID(ENTRY_A_ID)))
            assertNotNull("Reopened database B should contain its entry", loadedB.getEntryById(NodeIdUUID(ENTRY_B_ID)))

            // Database.mergeData is the public path that constructs DatabaseKDBXMerger internally.
            fileB.inputStream().use { inputStream ->
                loadedA.mergeData(
                    databaseToMergeStream = inputStream,
                    databaseToMergeMasterCredential = masterCredential,
                    databaseToMergeChallengeResponseRetriever = NO_CHALLENGE_RESPONSE,
                    limits = TEST_LIMITS,
                    progressTaskUpdater = null
                )
            }

            val mergedFile = File(tempDir, "merged.kdbx")
            saveDatabase(loadedA, mergedFile, masterCredential)

            val reopenedMerged = loadDatabase(mergedFile, masterCredential, tempDir)
            val mergedGroup = reopenedMerged.getGroupById(NodeIdUUID(SHARED_GROUP_ID))
            assertNotNull("Shared group should still exist after round-trip merge", mergedGroup)

            val mergedTitles = mergedGroup!!.getChildEntries().map { it.title }
            assertEquals(1, mergedTitles.count { it == ENTRY_FROM_A })
            assertEquals(1, mergedTitles.count { it == ENTRY_FROM_B })
            assertNotNull(reopenedMerged.getEntryById(NodeIdUUID(ENTRY_A_ID)))
            assertNotNull(reopenedMerged.getEntryById(NodeIdUUID(ENTRY_B_ID)))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createDatabaseFromSameBase(): DatabaseKDBX {
        val database = createEmptyDatabase()
        addEntry(
            database = database,
            parentGroupId = SHARED_GROUP_ID,
            entryId = SHARED_BASE_ENTRY_ID,
            title = SHARED_BASE_ENTRY_TITLE,
            lastModificationTime = DateInstant("2000-01-01T00:00Z")
        )
        return database
    }

    private fun createEmptyDatabase(): DatabaseKDBX {
        val database = DatabaseKDBX(
            databaseName = "merge-roundtrip-test-db",
            rootName = "root",
            templatesGroupName = null
        )

        val rootGroup = requireNotNull(database.rootGroup)
        val sharedGroup = database.createGroup().apply {
            nodeId = NodeIdUUID(SHARED_GROUP_ID)
            title = SHARED_GROUP_NAME
        }
        database.addGroupTo(sharedGroup, rootGroup)
        return database
    }

    private fun addEntry(
        database: DatabaseKDBX,
        parentGroupId: UUID,
        entryId: UUID,
        title: String,
        lastModificationTime: DateInstant? = null
    ) {
        val parent = requireNotNull(database.getGroupById(parentGroupId))
        val entry = database.createEntry().apply {
            nodeId = NodeIdUUID(entryId)
            this.title = title
            username = "user-$title"
            password = "password-$title".toCharArray()
            if (lastModificationTime != null) {
                creationTime = DateInstant(lastModificationTime)
                this.lastModificationTime = DateInstant(lastModificationTime)
            }
        }
        database.addEntryTo(entry, parent)
    }

    private fun saveDatabase(
        database: DatabaseKDBX,
        file: File,
        masterCredential: MasterCredential
    ) {
        database.kdfEngine?.setKeyRounds(TEST_KDF_ROUNDS)
        file.outputStream().use { outputStream ->
            DatabaseOutputKDBX(database).writeDatabase(outputStream) {
                database.deriveMasterKey(masterCredential, NO_CHALLENGE_RESPONSE)
            }
        }
    }

    private fun saveDatabase(
        database: Database,
        file: File,
        masterCredential: MasterCredential
    ) {
        database.kdfEngine?.setKeyRounds(TEST_KDF_ROUNDS)
        database.saveData(
            cacheFile = File.createTempFile("database-merge-cache-", ".kdbx", file.parentFile),
            databaseOutputStream = { file.outputStream() },
            masterCredential = masterCredential,
            challengeResponseRetriever = NO_CHALLENGE_RESPONSE,
            limits = TEST_LIMITS
        )
    }

    private fun loadDatabase(
        file: File,
        masterCredential: MasterCredential,
        cacheDirectory: File
    ): Database {
        return Database().apply {
            file.inputStream().use { inputStream ->
                loadData(
                    databaseStream = inputStream,
                    masterCredential = masterCredential,
                    challengeResponseRetriever = NO_CHALLENGE_RESPONSE,
                    readOnly = false,
                    allowUserVerification = false,
                    cacheDirectory = cacheDirectory,
                    limits = TEST_LIMITS,
                    fixDuplicateUUID = false,
                    progressTaskUpdater = null
                )
            }
        }
    }

    companion object {
        private val TEST_LIMITS = Limits(
            isMemorySufficient = { _, _ -> true },
            parallelism = 1L
        )
        private val NO_CHALLENGE_RESPONSE: (HardwareKey, ByteArray?) -> ByteArray = { _, _ -> ByteArray(0) }
        private val TEST_KDF_ROUNDS = 1uL
        private const val TEST_PASSWORD = "merge-test-password"

        private val SHARED_GROUP_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        private val SHARED_BASE_ENTRY_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        private val ENTRY_A_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        private val ENTRY_B_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        private const val SHARED_GROUP_NAME = "General"
        private const val SHARED_BASE_ENTRY_TITLE = "shared-base-entry"
        private const val ENTRY_FROM_A = "entry-from-a"
        private const val ENTRY_FROM_B = "entry-from-b"
    }
}
