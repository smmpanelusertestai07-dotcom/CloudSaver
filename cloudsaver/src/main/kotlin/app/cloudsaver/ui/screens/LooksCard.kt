package app.cloudsaver.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cloudsaver.R
import app.cloudsaver.util.AppLooks

/**
 * Ente Saver's name on the home screen: "Ente Saver", or "CloudSaver" as earlier versions were called,
 * with the one Ente Saver icon either way. The switch happens when the app
 * goes to the background, and launchers take a moment to redraw.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LooksCard(chosen: AppLooks.Look, icon: ImageVector, onChoose: (AppLooks.Look) -> Unit) {
    OptionCard(
        stringResource(R.string.opt_looks),
        stringResource(R.string.opt_looks_hint),
        icon = icon,
        value = stringResource(chosen.nameRes)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            for (look in AppLooks.Look.entries) {
                val selected = look == chosen
                val shape = RoundedCornerShape(12.dp)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(76.dp)
                        .clip(shape)
                        .selectable(selected = selected, role = Role.RadioButton) { onChoose(look) }
                        .then(
                            if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
                        )
                        .padding(6.dp)
                ) {
                    LookIcon()
                    Text(
                        stringResource(look.nameRes),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        ChoiceNote(stringResource(R.string.looks_note))
    }
}

/** Ente Saver's icon, drawn from the same two layers the launcher uses. */
@Composable
private fun LookIcon() {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(percent = 24))
    ) {
        for (layer in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)) {
            Image(
                painterResource(layer),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // The launcher shows the middle two thirds of a layer.
                modifier = Modifier
                    .matchParentSize()
                    .scale(1.5f)
            )
        }
    }
}
