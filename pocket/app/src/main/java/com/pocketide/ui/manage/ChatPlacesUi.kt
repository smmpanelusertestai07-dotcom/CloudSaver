package com.pocketide.ui.manage

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.pocketide.core.Settings
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.shell.External
import com.pocketide.ui.shell.Links

/** Each official agent's answer to "where are my chats saved?", with the company page that shows them. */
@Composable
fun ChatPlacesCard(settings: Settings, nav: PocketNav) {
    val context = LocalContext.current
    SectionCard(null) {
        ChatPlaces.all(settings).forEachIndexed { index, place ->
            if (index > 0) HorizontalDivider()
            Text(place.agent, style = MaterialTheme.typography.titleSmall)
            Hint(place.kept)
            place.note?.let { Hint(it) }
            if (place.page != null && place.pageLabel != null) {
                LinkRow(place.pageLabel, place.page, nav) { openChatPage(context, nav, place) }
            }
        }
    }
}

/** "Where this chat is saved", from an agent's session menu. */
@Composable
internal fun ChatPlaceDialog(place: ChatPlaces.Place, nav: PocketNav, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where this chat is saved") },
        text = {
            Column {
                Text(place.kept, style = MaterialTheme.typography.bodyMedium)
                place.note?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (place.page != null && place.pageLabel != null) {
                TextButton(onClick = {
                    onDismiss()
                    openChatPage(context, nav, place)
                }) { Text(place.pageLabel) }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = { if (place.page != null) TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Opens [place]'s page: in the company's own app when it has one installed, in the browser otherwise. */
internal fun openChatPage(context: Context, nav: PocketNav, place: ChatPlaces.Place) {
    val page = place.page ?: return
    if (!place.inOwnApp || !openInApp(context, page)) nav.openExternal(page)
}

/**
 * Opens [url] in an installed app that claims it (the Claude app for claude.ai), never in a
 * browser; false when no such app is there, so the caller opens it in Chrome as usual.
 */
private fun openInApp(context: Context, url: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Links.isOpenable(url)) return false
    val view = Intent(Intent.ACTION_VIEW, url.trim().toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
    if (context !is Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        External.leaving(context)
        context.startActivity(view)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
