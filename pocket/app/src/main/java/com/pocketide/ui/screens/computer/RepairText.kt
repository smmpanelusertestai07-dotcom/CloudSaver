package com.pocketide.ui.screens.computer

import com.pocketide.linux.RepairItem
import com.pocketide.linux.RepairStatus
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.Told

/** How the Repair report reads: problems first, one chip per item, one line for the snackbar. */
internal object RepairText {
    fun chip(status: RepairStatus): Told = when (status) {
        RepairStatus.OK -> Told("OK", Tone.OK)
        RepairStatus.NEW -> Told("Fixed", Tone.OK)
        RepairStatus.WARN -> Told("Needs you", Tone.WARN)
        RepairStatus.NOTE -> Told("Note", Tone.NEUTRAL)
    }

    fun ordered(items: List<RepairItem>): List<RepairItem> = items.sortedBy { rank(it.status) }

    fun summary(items: List<RepairItem>): String {
        val warn = items.count { it.status == RepairStatus.WARN }
        val fixed = items.count { it.status == RepairStatus.NEW }
        return when {
            warn > 0 -> "Repair finished. ${count(warn, "item")} could not be put right: see below."
            fixed > 0 -> "Repair finished. ${count(fixed, "item")} fixed."
            else -> "Repair finished. Everything was already right."
        }
    }

    private fun count(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    private fun rank(status: RepairStatus) = when (status) {
        RepairStatus.WARN -> 0
        RepairStatus.NEW -> 1
        RepairStatus.NOTE -> 2
        RepairStatus.OK -> 3
    }
}
