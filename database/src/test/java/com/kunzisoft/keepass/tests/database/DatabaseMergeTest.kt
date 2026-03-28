package com.kunzisoft.keepass.tests.database

import com.kunzisoft.keepass.database.element.database.DatabaseKDBX
import com.kunzisoft.keepass.database.element.DateInstant
import com.kunzisoft.keepass.database.element.node.NodeIdUUID
import com.kunzisoft.keepass.database.merge.DatabaseKDBXMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class DatabaseMergeTest {

    @Test
    fun merge_shouldIncludeEntriesAddedInOtherFile() {
        val databaseA = createDatabaseFromSameBase()
        val databaseB = createDatabaseFromSameBase()

        addEntry(databaseA, SHARED_GROUP_ID, ENTRY_A_ID, ENTRY_FROM_A)
        addEntry(databaseB, SHARED_GROUP_ID, ENTRY_B_ID, ENTRY_FROM_B)

        DatabaseKDBXMerger(databaseA).apply {
            isRAMSufficient = { true }
        }.merge(databaseB)

        val mergedGroup = databaseA.getGroupById(SHARED_GROUP_ID)
        assertNotNull("Shared group should still exist after merge", mergedGroup)

        val mergedTitles = mergedGroup!!.getChildEntries().map { it.title }
        assertEquals(1, mergedTitles.count { it == ENTRY_FROM_A })
        assertEquals(1, mergedTitles.count { it == ENTRY_FROM_B })
    }

    @Test
    fun merge_shouldKeepDeletionFromAAndAdditionFromB() {
        val databaseA = createDatabaseFromSameBase()
        val databaseB = createDatabaseFromSameBase()

        deleteEntry(databaseA, SHARED_BASE_ENTRY_ID)
        addEntry(databaseB, SHARED_GROUP_ID, ENTRY_C_ID, ENTRY_FROM_C)

        DatabaseKDBXMerger(databaseA).apply {
            isRAMSufficient = { true }
        }.merge(databaseB)

        val mergedGroup = databaseA.getGroupById(SHARED_GROUP_ID)
        assertNotNull("Shared group should still exist after merge", mergedGroup)

        val mergedTitles = mergedGroup!!.getChildEntries().map { it.title }
        assertEquals(0, mergedTitles.count { it == SHARED_BASE_ENTRY_TITLE })
        assertEquals(1, mergedTitles.count { it == ENTRY_FROM_C })
        assertNull(databaseA.getEntryById(SHARED_BASE_ENTRY_ID))
        assertNotNull(databaseA.getEntryById(ENTRY_C_ID))
    }

    @Test
    fun merge_bMergeA_afterADeletesABCD_andBAddsF_shouldKeepOnlyEAndF() {
        val baseEntries = listOf(
            ENTRY_A_BASE_ID to "a",
            ENTRY_B_BASE_ID to "b",
            ENTRY_C_BASE_ID to "c",
            ENTRY_D_BASE_ID to "d",
            ENTRY_E_BASE_ID to "e"
        )
        val databaseA = createDatabaseFromEntries(baseEntries)
        val databaseB = createDatabaseFromEntries(baseEntries)

        deleteEntry(databaseA, ENTRY_A_BASE_ID)
        deleteEntry(databaseA, ENTRY_B_BASE_ID)
        deleteEntry(databaseA, ENTRY_C_BASE_ID)
        deleteEntry(databaseA, ENTRY_D_BASE_ID)

        addEntry(databaseB, SHARED_GROUP_ID, ENTRY_F_ID, "f")

        DatabaseKDBXMerger(databaseB).apply {
            isRAMSufficient = { true }
        }.merge(databaseA)

        val mergedTitles = getSharedGroupEntryTitles(databaseB)
        assertEquals(0, mergedTitles.count { it == "a" })
        assertEquals(0, mergedTitles.count { it == "b" })
        assertEquals(0, mergedTitles.count { it == "c" })
        assertEquals(0, mergedTitles.count { it == "d" })
        assertEquals(1, mergedTitles.count { it == "e" })
        assertEquals(1, mergedTitles.count { it == "f" })
        assertEquals(2, mergedTitles.size)
    }

    @Test
    fun merge_shouldApplyRemoteRecycleBinMovesEvenWhenLocalEntryIsNewer() {
        val baseEntries = listOf(
            ENTRY_A_BASE_ID to "a",
            ENTRY_B_BASE_ID to "b",
            ENTRY_C_BASE_ID to "c",
            ENTRY_D_BASE_ID to "d",
            ENTRY_E_BASE_ID to "e"
        )
        val databaseA = createDatabaseFromEntries(baseEntries)
        val databaseB = createDatabaseFromEntries(baseEntries)

        setEntryLastModificationTime(databaseA, ENTRY_B_BASE_ID, DateInstant("2030-01-01T00:00Z"))
        setEntryLastModificationTime(databaseA, ENTRY_D_BASE_ID, DateInstant("2030-01-01T00:00Z"))

        moveEntryToRecycleBin(databaseB, ENTRY_A_BASE_ID, DateInstant("2040-01-01T00:00Z"))
        moveEntryToRecycleBin(databaseB, ENTRY_B_BASE_ID, DateInstant("2040-01-01T00:00Z"))
        moveEntryToRecycleBin(databaseB, ENTRY_C_BASE_ID, DateInstant("2040-01-01T00:00Z"))
        moveEntryToRecycleBin(databaseB, ENTRY_D_BASE_ID, DateInstant("2040-01-01T00:00Z"))

        DatabaseKDBXMerger(databaseA).apply {
            isRAMSufficient = { true }
        }.merge(databaseB)

        val mergedTitles = getSharedGroupEntryTitles(databaseA)
        assertEquals(0, mergedTitles.count { it == "a" })
        assertEquals(0, mergedTitles.count { it == "b" })
        assertEquals(0, mergedTitles.count { it == "c" })
        assertEquals(0, mergedTitles.count { it == "d" })
        assertEquals(1, mergedTitles.count { it == "e" })
        assertEquals(1, mergedTitles.size)
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

    private fun createDatabaseFromEntries(entries: List<Pair<UUID, String>>): DatabaseKDBX {
        val database = createEmptyDatabase()
        entries.forEach { (entryId, title) ->
            addEntry(
                database = database,
                parentGroupId = SHARED_GROUP_ID,
                entryId = entryId,
                title = title,
                lastModificationTime = DateInstant("2000-01-01T00:00Z")
            )
        }
        return database
    }

    private fun createEmptyDatabase(): DatabaseKDBX {
        val database = DatabaseKDBX(
            databaseName = "merge-test-db",
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
            password = "password-$title"
            if (lastModificationTime != null) {
                creationTime = DateInstant(lastModificationTime)
                this.lastModificationTime = DateInstant(lastModificationTime)
            }
        }
        database.addEntryTo(entry, parent)
    }

    private fun deleteEntry(database: DatabaseKDBX, entryId: UUID) {
        val entry = requireNotNull(database.getEntryById(entryId))
        database.removeEntryFrom(entry, entry.parent)
        database.addDeletedObject(entryId)
    }

    private fun setEntryLastModificationTime(database: DatabaseKDBX, entryId: UUID, dateInstant: DateInstant) {
        val entry = requireNotNull(database.getEntryById(entryId))
        entry.lastModificationTime = DateInstant(dateInstant)
    }

    private fun moveEntryToRecycleBin(database: DatabaseKDBX, entryId: UUID, locationChanged: DateInstant) {
        database.ensureRecycleBinExists(RECYCLE_BIN_TITLE)
        val recycleBin = requireNotNull(database.recycleBin)
        val entry = requireNotNull(database.getEntryById(entryId))
        database.removeEntryFrom(entry, entry.parent)
        database.addEntryTo(entry, recycleBin)
        entry.locationChanged = DateInstant(locationChanged)
    }

    private fun getSharedGroupEntryTitles(database: DatabaseKDBX): List<String> {
        val group = requireNotNull(database.getGroupById(SHARED_GROUP_ID))
        return group.getChildEntries().map { it.title }
    }

    companion object {
        private val SHARED_GROUP_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        private val SHARED_BASE_ENTRY_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        private val ENTRY_A_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        private val ENTRY_B_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        private val ENTRY_C_ID: UUID = UUID.fromString("66666666-6666-6666-6666-666666666666")
        private val ENTRY_A_BASE_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1")
        private val ENTRY_B_BASE_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2")
        private val ENTRY_C_BASE_ID: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc3")
        private val ENTRY_D_BASE_ID: UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd4")
        private val ENTRY_E_BASE_ID: UUID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee5")
        private val ENTRY_F_ID: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff6")

        private const val SHARED_GROUP_NAME = "General"
        private const val SHARED_BASE_ENTRY_TITLE = "shared-base-entry"
        private const val ENTRY_FROM_A = "entry-from-a"
        private const val ENTRY_FROM_B = "entry-from-b"
        private const val ENTRY_FROM_C = "entry-from-c"
        private const val RECYCLE_BIN_TITLE = "Recycle Bin"
    }
}
