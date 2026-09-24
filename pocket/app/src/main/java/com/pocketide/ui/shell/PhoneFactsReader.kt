package com.pocketide.ui.shell

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.pocketide.AppGraph

/** Reads the facts the requirement check needs, straight from Android (no module needed yet). */
object PhoneFactsReader {
    fun read(context: Context, graph: AppGraph): PhoneFacts {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
        val free = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        val play = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrNull()
        return PhoneFacts(
            androidSdk = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            arm64 = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"),
            playServices = play,
            totalRamBytes = memory.totalMem,
            freeStorageBytes = free,
            screenLock = runCatching { graph.appLock.deviceSecure() }.getOrDefault(false),
        )
    }
}
