package com.kunzisoft.keepass.database.action

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncChangeSummaryTest {

    @Test
    fun deletedEntryIsCounted() {
        val counts = SyncChangeSummary.compute(
            before = mapOf("1" to snapshot("100#root", inRecycleBin = false)),
            after = emptyMap()
        )

        assertEquals(SyncChangeSummary.Counts(added = 0, deleted = 1, modified = 0), counts)
    }

    @Test
    fun entryRestoredFromRecycleBinIsAdded() {
        val counts = SyncChangeSummary.compute(
            before = mapOf("1" to snapshot("100#root", inRecycleBin = true)),
            after = mapOf("1" to snapshot("100#root", inRecycleBin = false))
        )

        assertEquals(SyncChangeSummary.Counts(added = 1, deleted = 0, modified = 0), counts)
    }

    @Test
    fun unchangedEntryHasNoCounts() {
        val counts = SyncChangeSummary.compute(
            before = mapOf("1" to snapshot("100#root", inRecycleBin = false)),
            after = mapOf("1" to snapshot("100#root", inRecycleBin = false))
        )

        assertEquals(SyncChangeSummary.Counts(added = 0, deleted = 0, modified = 0), counts)
    }

    @Test
    fun newEntryAlreadyInRecycleBinIsIgnored() {
        val counts = SyncChangeSummary.compute(
            before = emptyMap(),
            after = mapOf("1" to snapshot("100#recycle", inRecycleBin = true))
        )

        assertEquals(SyncChangeSummary.Counts(added = 0, deleted = 0, modified = 0), counts)
    }

    @Test
    fun mixedChangesAreCountedTogether() {
        val counts = SyncChangeSummary.compute(
            before = mapOf(
                "added" to snapshot("100#root", inRecycleBin = false),
                "deleted" to snapshot("100#root", inRecycleBin = false),
                "modified" to snapshot("100#root", inRecycleBin = false),
                "unchanged" to snapshot("100#root", inRecycleBin = false),
                "recycled" to snapshot("100#root", inRecycleBin = false)
            ),
            after = mapOf(
                "added" to snapshot("100#root", inRecycleBin = false),
                "modified" to snapshot("200#root", inRecycleBin = false),
                "unchanged" to snapshot("100#root", inRecycleBin = false),
                "recycled" to snapshot("100#root", inRecycleBin = true),
                "new" to snapshot("100#root", inRecycleBin = false)
            )
        )

        assertEquals(SyncChangeSummary.Counts(added = 1, deleted = 2, modified = 1), counts)
    }

    private fun snapshot(fingerprint: String, inRecycleBin: Boolean): SyncChangeSummary.Snapshot {
        return SyncChangeSummary.Snapshot(
            fingerprint = fingerprint,
            inRecycleBin = inRecycleBin
        )
    }
}
