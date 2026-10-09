package com.kunzisoft.keepass.tests.database

import com.kunzisoft.keepass.database.element.database.DatabaseKDBX
import com.kunzisoft.keepass.database.element.DateInstant
import com.kunzisoft.keepass.database.element.node.NodeIdUUID
import com.kunzisoft.keepass.database.element.DeletedObject
import com.kunzisoft.keepass.database.merge.DatabaseKDBXMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DatabaseMergeTest {

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

        addEntry(databaseB, SHARED_GROUP_ID, ENTRY_F_ID, "f", DateInstant("2010-01-01T00:00Z"))

        merge(databaseB, databaseA)

        assertEquals(setOf("e", "f"), sharedGroupEntryTitles(databaseB))
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

        merge(databaseA, databaseB)

        assertEquals(setOf("e"), sharedGroupEntryTitles(databaseA))
    }

    @Test
    fun merge_shouldApplyRemoteGroupMoveEvenWhenLocalGroupIsNewer() {
        val databaseA = createEmptyDatabase()
        val databaseB = createEmptyDatabase()

        addGroup(databaseA, SHARED_GROUP_ID, MOVABLE_GROUP_ID, "movable")
        addGroup(databaseA, SHARED_GROUP_ID, TARGET_GROUP_ID, "target")
        addGroup(databaseB, SHARED_GROUP_ID, MOVABLE_GROUP_ID, "movable")
        addGroup(databaseB, SHARED_GROUP_ID, TARGET_GROUP_ID, "target")

        // The moved group has newer local content...
        setGroupLastModificationTime(databaseA, MOVABLE_GROUP_ID, DateInstant("2030-01-01T00:00Z"))
        // ...but the remote instance moved it more recently.
        moveGroupToParent(databaseB, MOVABLE_GROUP_ID, TARGET_GROUP_ID, DateInstant("2040-01-01T00:00Z"))

        merge(databaseA, databaseB)

        val movedGroup = requireNotNull(databaseA.getGroupById(MOVABLE_GROUP_ID))
        assertEquals(NodeIdUUID(TARGET_GROUP_ID), movedGroup.parent?.nodeId)
    }

    @Test
    fun merge_bothSidesChanged_shouldConvergeWhenMergedFromEachSide() {
        val databaseA = buildSharedBase()
        val databaseB = buildSharedBase()

        // Local changes in A
        deleteEntry(databaseA, ENTRY_DEL_ID)
        setEntryTitle(databaseA, ENTRY_MOD_ID, "modified-by-a")
        addEntry(databaseA, SHARED_GROUP_ID, ENTRY_ADD_A_ID, "added-by-a", DateInstant("2010-01-01T00:00Z"))

        // Concurrent changes in B
        setEntryTitle(databaseB, ENTRY_MOD_ID, "modified-by-b", DateInstant("2020-01-01T00:00Z"))
        addEntry(databaseB, SHARED_GROUP_ID, ENTRY_ADD_B_ID, "added-by-b", DateInstant("2010-01-01T00:00Z"))

        // Each side merges the other; B merges the already merged A.
        merge(databaseA, databaseB)
        merge(databaseB, databaseA)

        val expected = setOf("keep", "modified-by-a", "added-by-a", "added-by-b")
        assertEquals(expected, sharedGroupEntryTitles(databaseA))
        assertEquals(expected, sharedGroupEntryTitles(databaseB))
    }

    @Test
    fun merge_shouldBeIdempotentWhenTheSameRemoteIsMergedTwice() {
        val databaseA = buildSharedBase()
        val databaseB = buildSharedBase()

        addEntry(databaseA, SHARED_GROUP_ID, ENTRY_ADD_A_ID, "added-by-a", DateInstant("2010-01-01T00:00Z"))
        setEntryTitle(databaseB, ENTRY_MOD_ID, "modified-by-b", DateInstant("2020-01-01T00:00Z"))

        merge(databaseA, databaseB)
        val titlesAfterFirstMerge = sharedGroupEntryTitles(databaseA)
        val historyAfterFirstMerge = entryHistorySize(databaseA, ENTRY_MOD_ID)

        merge(databaseA, databaseB)

        assertEquals(titlesAfterFirstMerge, sharedGroupEntryTitles(databaseA))
        assertEquals(historyAfterFirstMerge, entryHistorySize(databaseA, ENTRY_MOD_ID))
    }

    @Test
    fun merge_whenRemoteEntryIsNewer_localRevisionIsKeptInHistory() {
        val databaseA = buildSharedBase()
        val databaseB = buildSharedBase()

        setEntryTitle(databaseA, ENTRY_MOD_ID, "local-older", DateInstant("2020-01-01T00:00Z"))
        setEntryTitle(databaseB, ENTRY_MOD_ID, "remote-newer")

        merge(databaseA, databaseB)

        val entry = requireNotNull(databaseA.getEntryById(ENTRY_MOD_ID))
        assertEquals("remote-newer", entry.title)
        assertTrue(entryHistoryTitles(databaseA, ENTRY_MOD_ID).contains("local-older"))
    }

    @Test
    fun merge_whenRemoteDeletedEntryAndLocalModifiedItLater_localChangeWins() {
        val databaseA = buildSharedBase()
        val databaseB = buildSharedBase()

        setEntryTitle(databaseA, ENTRY_MOD_ID, "modified-locally", DateInstant("2040-01-01T00:00Z"))
        deleteEntry(databaseB, ENTRY_MOD_ID, DateInstant("2030-01-01T00:00Z"))

        merge(databaseA, databaseB)

        val entry = databaseA.getEntryById(ENTRY_MOD_ID)
        assertNotNull(entry)
        assertEquals("modified-locally", entry!!.title)
    }

    @Test
    fun merge_whenRemoteDeletedEntryAndLocalModifiedItEarlier_remoteDeletionWins() {
        val databaseA = buildSharedBase()
        val databaseB = buildSharedBase()

        setEntryTitle(databaseA, ENTRY_MOD_ID, "modified-locally", DateInstant("2020-01-01T00:00Z"))
        deleteEntry(databaseB, ENTRY_MOD_ID, DateInstant("2030-01-01T00:00Z"))

        merge(databaseA, databaseB)

        assertNull(databaseA.getEntryById(ENTRY_MOD_ID))
    }

    @Test
    fun merge_whenRemoteDeletedEntryAndLocalMovedIt_remoteDeletionWins() {
        val databaseA = buildSharedBase()
        val databaseB = buildSharedBase()

        addGroup(databaseA, SHARED_GROUP_ID, TARGET_GROUP_ID, "target")
        moveEntryToGroup(databaseA, ENTRY_MOD_ID, TARGET_GROUP_ID)
        deleteEntry(databaseB, ENTRY_MOD_ID, DateInstant("2030-01-01T00:00Z"))

        merge(databaseA, databaseB)

        assertNull(databaseA.getEntryById(ENTRY_MOD_ID))
    }

    @Test
    fun merge_whenLocalEntryIsNewer_keepsLocalFieldsAndRecordsRemoteRevisionInHistory() {
        val databaseA = buildSharedBase()
        val databaseB = buildSharedBase()

        setEntryFields(databaseA, ENTRY_MOD_ID, "local", "local-user", "local-pass", DateInstant("2030-01-01T00:00Z"))
        setEntryFields(databaseB, ENTRY_MOD_ID, "remote", "remote-user", "remote-pass", DateInstant("2020-01-01T00:00Z"))

        merge(databaseA, databaseB)

        val entry = requireNotNull(databaseA.getEntryById(ENTRY_MOD_ID))
        assertEquals("local", entry.title)
        assertEquals("local-user", entry.username)
        assertEquals("local-pass", entry.password.concatToString())

        val remoteRevision = requireNotNull(entry.history.find { it.username == "remote-user" })
        assertEquals("remote", remoteRevision.title)
        assertEquals("remote-pass", remoteRevision.password.concatToString())
    }

    @Test
    fun merge_whenRemoteUpdatedEntryAfterLocalDeletion_revivesTheEntry() {
        val databaseA = buildSharedBase()
        val databaseB = buildSharedBase()

        deleteEntry(databaseA, ENTRY_MOD_ID, DateInstant("2010-01-01T00:00Z"))
        setEntryTitle(databaseB, ENTRY_MOD_ID, "revived", DateInstant("2020-01-01T00:00Z"))

        merge(databaseA, databaseB)

        val entry = databaseA.getEntryById(ENTRY_MOD_ID)
        assertNotNull(entry)
        assertEquals("revived", entry!!.title)
    }

    @Test
    fun merge_whenRemoteUpdatedEntryBeforeLocalDeletion_keepsItDeleted() {
        val databaseA = buildSharedBase()
        val databaseB = buildSharedBase()

        deleteEntry(databaseA, ENTRY_MOD_ID, DateInstant("2030-01-01T00:00Z"))
        setEntryTitle(databaseB, ENTRY_MOD_ID, "too-old", DateInstant("2020-01-01T00:00Z"))

        merge(databaseA, databaseB)

        assertNull(databaseA.getEntryById(ENTRY_MOD_ID))
    }

    private fun buildSharedBase(): DatabaseKDBX {
        val database = createEmptyDatabase()
        addEntry(database, SHARED_GROUP_ID, ENTRY_KEEP_ID, "keep", DateInstant("2000-01-01T00:00Z"))
        addEntry(database, SHARED_GROUP_ID, ENTRY_DEL_ID, "deleted-by-a", DateInstant("2000-01-01T00:00Z"))
        addEntry(database, SHARED_GROUP_ID, ENTRY_MOD_ID, "modified", DateInstant("2000-01-01T00:00Z"))
        return database
    }

    private fun merge(target: DatabaseKDBX, source: DatabaseKDBX) {
        DatabaseKDBXMerger(target).apply {
            isRAMSufficient = { true }
        }.merge(source)
    }

    private fun setEntryTitle(
        database: DatabaseKDBX,
        entryId: UUID,
        title: String,
        lastModificationTime: DateInstant = DateInstant("2030-01-01T00:00Z")
    ) {
        val entry = requireNotNull(database.getEntryById(entryId))
        entry.title = title
        entry.lastModificationTime = DateInstant(lastModificationTime)
    }

    private fun setEntryFields(
        database: DatabaseKDBX,
        entryId: UUID,
        title: String,
        username: String,
        password: String,
        lastModificationTime: DateInstant
    ) {
        val entry = requireNotNull(database.getEntryById(entryId))
        entry.title = title
        entry.username = username
        entry.password = password.toCharArray()
        entry.lastModificationTime = DateInstant(lastModificationTime)
    }

    private fun entryHistorySize(database: DatabaseKDBX, entryId: UUID): Int {
        return requireNotNull(database.getEntryById(entryId)).history.size
    }

    private fun sharedGroupEntryTitles(database: DatabaseKDBX): Set<String> {
        val group = requireNotNull(database.getGroupById(SHARED_GROUP_ID))
        return group.getChildEntries().map { it.title }.toSet()
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
        lastModificationTime: DateInstant
    ) {
        val parent = requireNotNull(database.getGroupById(parentGroupId))
        val entry = database.createEntry().apply {
            nodeId = NodeIdUUID(entryId)
            this.title = title
            username = "user-$title"
            password = "password-$title".toCharArray()
            creationTime = DateInstant(lastModificationTime)
            this.lastModificationTime = DateInstant(lastModificationTime)
        }
        database.addEntryTo(entry, parent)
    }

    private fun deleteEntry(
        database: DatabaseKDBX,
        entryId: UUID,
        deletionTime: DateInstant = DateInstant()
    ) {
        val entry = requireNotNull(database.getEntryById(entryId))
        database.removeEntryFrom(entry, entry.parent)
        database.addDeletedObject(DeletedObject(entryId, deletionTime))
    }

    private fun moveEntryToGroup(database: DatabaseKDBX, entryId: UUID, newParentId: UUID) {
        val entry = requireNotNull(database.getEntryById(entryId))
        val newParent = requireNotNull(database.getGroupById(newParentId))
        database.removeEntryFrom(entry, entry.parent)
        database.addEntryTo(entry, newParent)
        entry.locationChanged = DateInstant("2020-01-01T00:00Z")
    }

    private fun entryHistoryTitles(database: DatabaseKDBX, entryId: UUID): List<String> {
        return requireNotNull(database.getEntryById(entryId)).history.map { it.title }
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

    private fun addGroup(
        database: DatabaseKDBX,
        parentGroupId: UUID,
        groupId: UUID,
        title: String
    ) {
        val parent = requireNotNull(database.getGroupById(parentGroupId))
        val group = database.createGroup().apply {
            nodeId = NodeIdUUID(groupId)
            this.title = title
            lastModificationTime = DateInstant("2000-01-01T00:00Z")
            locationChanged = DateInstant("2000-01-01T00:00Z")
        }
        database.addGroupTo(group, parent)
    }

    private fun setGroupLastModificationTime(database: DatabaseKDBX, groupId: UUID, dateInstant: DateInstant) {
        val group = requireNotNull(database.getGroupById(groupId))
        group.lastModificationTime = DateInstant(dateInstant)
    }

    private fun moveGroupToParent(
        database: DatabaseKDBX,
        groupId: UUID,
        newParentId: UUID,
        locationChanged: DateInstant
    ) {
        val group = requireNotNull(database.getGroupById(groupId))
        val newParent = requireNotNull(database.getGroupById(newParentId))
        database.removeGroupFrom(group, group.parent)
        database.addGroupTo(group, newParent)
        group.locationChanged = DateInstant(locationChanged)
    }

    companion object {
        private val SHARED_GROUP_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        private val ENTRY_A_BASE_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1")
        private val ENTRY_B_BASE_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2")
        private val ENTRY_C_BASE_ID: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc3")
        private val ENTRY_D_BASE_ID: UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd4")
        private val ENTRY_E_BASE_ID: UUID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee5")
        private val ENTRY_F_ID: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff6")
        private val MOVABLE_GROUP_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
        private val TARGET_GROUP_ID: UUID = UUID.fromString("88888888-8888-8888-8888-888888888888")
        private val ENTRY_KEEP_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        private val ENTRY_DEL_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
        private val ENTRY_MOD_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000003")
        private val ENTRY_ADD_A_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000004")
        private val ENTRY_ADD_B_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000005")

        private const val SHARED_GROUP_NAME = "General"
        private const val RECYCLE_BIN_TITLE = "Recycle Bin"
    }
}
