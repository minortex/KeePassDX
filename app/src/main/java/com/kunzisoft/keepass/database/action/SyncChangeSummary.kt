package com.kunzisoft.keepass.database.action

object SyncChangeSummary {

    data class Snapshot(
        val fingerprint: String,
        val inRecycleBin: Boolean
    )

    data class Counts(
        val added: Int,
        val deleted: Int,
        val modified: Int
    )

    fun compute(
        before: Map<String, Snapshot>,
        after: Map<String, Snapshot>
    ): Counts {
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
        return Counts(added, deleted, modified)
    }
}
