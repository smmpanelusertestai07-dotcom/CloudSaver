package com.pocketide.ui.web

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.pocketide.R
import com.pocketide.cloudshell.IdePlace
import com.pocketide.core.ThemeMode
import com.pocketide.graph
import com.pocketide.ui.tools.IdeToolsActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * An agent's VS Code, or Cloud Shell's terminal or files, in a Chrome tab dressed as PocketIDE's:
 * the violet bar, a back arrow that returns to PocketIDE, the tools button (every agent with its
 * logo, the terminal, the files), and the other places in Chrome's menu (⋮). Google allows its
 * sign-in only in a real browser, so Cloud Shell cannot open inside PocketIDE itself.
 */
object IdeTab {
    const val EXTRA_PLACE = "com.pocketide.extra.PLACE"

    private val pending = MutableStateFlow<IdePlace?>(null)

    /** A place to open as soon as PocketIDE's screens are in front again, unlocked. */
    val next: StateFlow<IdePlace?> = pending.asStateFlow()

    /** Asks PocketIDE to open [place] in a new tab, in place of the one open now. */
    fun openNext(place: IdePlace) {
        pending.value = place
    }

    /** The place asked for, once. */
    fun takeNext(): IdePlace? = pending.getAndUpdate { null }

    fun open(context: Context, place: IdePlace) {
        val graph = context.graph
        val settings = graph.settings.settings.value
        graph.settings.update { it.copy(cloudOpenedAt = graph.clock.now()) }
        val builder = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setUrlBarHidingEnabled(false)
            .setBookmarksButtonEnabled(false)
            .setDownloadButtonEnabled(false)
            .setColorScheme(scheme(settings.theme))
            .setDefaultColorSchemeParams(bar(context, R.color.brand_tile_flat))
            .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, bar(context, R.color.brand_tile_bottom))
        icon(context, R.drawable.ic_tab_back)?.let(builder::setCloseButtonIcon)
        icon(context, R.drawable.ic_tab_tools)?.let { builder.setActionButton(it, "PocketIDE tools", tools(context, null), true) }
        IdePlace.entries.filter { it != place }.forEach { builder.addMenuItem(it.label, tools(context, it)) }
        Browser.launch(context, builder.build(), place.url(settings.cloudAccount))
    }

    /** The tools sheet, or with [place] straight to that place. Each has its own request code, so each keeps its own place. */
    private fun tools(context: Context, place: IdePlace?): PendingIntent {
        val intent = Intent(context, IdeToolsActivity::class.java).putExtra(EXTRA_PLACE, place?.name)
        val code = place?.let { it.ordinal + 1 } ?: 0
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun bar(context: Context, colour: Int): CustomTabColorSchemeParams =
        CustomTabColorSchemeParams.Builder().setToolbarColor(ContextCompat.getColor(context, colour)).build()

    private fun scheme(theme: ThemeMode): Int = when (theme) {
        ThemeMode.SYSTEM -> CustomTabsIntent.COLOR_SCHEME_SYSTEM
        ThemeMode.LIGHT -> CustomTabsIntent.COLOR_SCHEME_LIGHT
        ThemeMode.DARK -> CustomTabsIntent.COLOR_SCHEME_DARK
    }

    /** Chrome takes its toolbar icons as 24 dp bitmaps. */
    private fun icon(context: Context, @DrawableRes id: Int): Bitmap? {
        val size = (ICON_DP * context.resources.displayMetrics.density).toInt()
        return ContextCompat.getDrawable(context, id)?.toBitmap(size, size)
    }

    private const val ICON_DP = 24
}
