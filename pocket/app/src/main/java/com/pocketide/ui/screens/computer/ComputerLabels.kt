package com.pocketide.ui.screens.computer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketide.cloud.Computer
import com.pocketide.cloud.ComputerState
import com.pocketide.cloud.OpenStep
import com.pocketide.ui.components.Formats
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor

/** How a computer's state reads on a chip. */
fun ComputerState.label(): String = when (this) {
    ComputerState.AVAILABLE -> "Running"
    ComputerState.CREATING -> "Being made"
    ComputerState.STARTING -> "Starting"
    ComputerState.STOPPING -> "Stopping"
    ComputerState.STOPPED -> "Stopped"
    ComputerState.UPDATING -> "Updating"
    ComputerState.FAILED -> "Failed"
    ComputerState.GONE -> "Deleted"
    ComputerState.UNKNOWN -> "Unknown"
}

fun ComputerState.tone(): Tone = when (this) {
    ComputerState.AVAILABLE -> Tone.OK
    ComputerState.FAILED, ComputerState.GONE -> Tone.ERROR
    else -> Tone.NEUTRAL
}

/** "2 cores · 8 GB RAM · 32 GB disk", from what GitHub reports. */
fun Computer.machineLine(): String? = machine?.let { m ->
    listOfNotNull(
        "${m.cpus} cores".takeIf { m.cpus > 0 },
        "${Formats.gigabytes(m.memoryBytes)} RAM".takeIf { m.memoryBytes > 0 },
        "${Formats.gigabytes(m.storageBytes)} disk".takeIf { m.storageBytes > 0 },
    ).joinToString(" · ").ifBlank { m.displayName }
}

/** The steps of opening a computer, ticked as they finish. */
@Composable
fun OpeningSteps(current: OpenStep, addsSetUp: Boolean, modifier: Modifier = Modifier) {
    val steps = buildList {
        add(OpenStep.CHECKING to "Checking the project")
        if (addsSetUp) add(OpenStep.ADDING_SET_UP to "Adding PocketIDE's set-up to the project")
        add(OpenStep.CREATING to "GitHub makes the computer (1 to 3 minutes, the first time only)")
        add(OpenStep.STARTING to "Starting the computer")
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        steps.forEach { (step, text) ->
            val done = current.ordinal > step.ordinal
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    done -> Icon(Icons.Outlined.CheckCircle, contentDescription = "Done", tint = toneColor(Tone.OK), modifier = Modifier.size(22.dp))
                    current == step -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else -> Icon(
                        Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done || current == step) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
