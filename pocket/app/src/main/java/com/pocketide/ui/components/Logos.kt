package com.pocketide.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketide.R
import com.pocketide.agents.Agent
import com.pocketide.core.AppJson
import com.pocketide.core.Http
import com.pocketide.core.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

/**
 * Each agent's own icon, as its publisher ships it with the extension on Open VSX, read when
 * shown and kept in memory only. Offline, the agent's initial stands in for it.
 */
@Composable
fun AgentLogo(agent: Agent, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val icon by produceState<ImageBitmap?>(initialValue = AgentIcons.cached(agent), agent) {
        if (value == null) value = AgentIcons.load(agent)
    }
    val shape = RoundedCornerShape(size * 0.26f)
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.size(size * 0.78f))
        } else {
            Text(
                agent.displayName.take(1),
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The GitHub mark (Octicons, MIT licence). */
@Composable
fun GitHubLogo(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Icon(painterResource(R.drawable.logo_github), contentDescription = "GitHub", modifier = modifier.size(size), tint = LocalContentColor.current)
}

/** The Visual Studio Code mark (Codicons, CC BY 4.0), in VS Code's blue. */
@Composable
fun VsCodeLogo(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Icon(painterResource(R.drawable.logo_vscode), contentDescription = "VS Code", modifier = modifier.size(size), tint = VS_CODE_BLUE)
}

private val VS_CODE_BLUE = androidx.compose.ui.graphics.Color(0xFF0078D4)

/** Reads icons once per process; a failed read is tried again the next time the icon is shown. */
private object AgentIcons {
    private val memory = LruCache<Agent, ImageBitmap>(Agent.entries.size)

    fun cached(agent: Agent): ImageBitmap? = memory.get(agent)

    suspend fun load(agent: Agent): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val info = get("https://open-vsx.org/api/${agent.publisher}/${agent.extensionName}")?.decodeToString() ?: return@withContext null
            val iconUrl = (AppJson.parseToJsonElement(info) as? JsonObject)
                ?.get("files")?.let { it as? JsonObject }
                ?.get("icon")?.jsonPrimitive?.content
                ?.takeIf { it.startsWith(OPEN_VSX) }
                ?: return@withContext null
            val bytes = get(iconUrl) ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { memory.put(agent, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private suspend fun get(url: String): ByteArray? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        return Http.client.newCall(Request.Builder().url(parsed).build()).await().use { response ->
            val small = response.isSuccessful && response.body.contentLength() <= MAX_BYTES
            if (small) response.body.bytes().takeIf { it.size <= MAX_BYTES } else null
        }
    }

    private const val OPEN_VSX = "https://open-vsx.org/"
    private const val MAX_BYTES = 512 * 1024
}
