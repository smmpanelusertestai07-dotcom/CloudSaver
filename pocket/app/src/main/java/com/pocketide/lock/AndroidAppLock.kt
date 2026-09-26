package com.pocketide.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pocketide.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The app lock over the phone's own fingerprint, face or screen lock, and nothing softer.
 *
 * Fail closed: an error, a cancel, a prompt that cannot start or a phone without a screen lock
 * all leave it locked; only onAuthenticationSucceeded opens it. The phone's screen lock is the
 * root of trust, so the prompt always offers it next to biometrics.
 */
internal class AndroidAppLock(
    private val context: Context,
    settings: SettingsStore,
    scope: CoroutineScope,
) : AppLock {
    private val latch = LockLatch(enabled = settings.settings.value.appLock, elapsed = SystemClock::elapsedRealtime)
    override val unlocked: StateFlow<Boolean> = latch.unlocked

    init {
        scope.launch { settings.settings.collect { latch.setEnabled(it.appLock) } }
    }

    override fun lockNow() = latch.lockNow()

    override fun onBackground() = latch.onBackground()

    override fun onForeground() = latch.onForeground()

    override fun leavingOnErrand() = latch.onErrand()

    override fun openForSetUp() = latch.passed()

    override fun deviceSecure(): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)

    override fun authenticate(activity: FragmentActivity, title: String, onDone: (Boolean) -> Unit) {
        // Nothing to check against: never invent a pass.
        if (!deviceSecure()) {
            onDone(false)
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                latch.passed()
                onDone(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone(false)
            // onAuthenticationFailed (a finger not recognised) keeps Android's sheet up for another try.
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Use your fingerprint, face or the phone's screen lock")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        try {
            // The prompt's own credential screen is a trip out of the app, not an absence.
            latch.onErrand()
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
        } catch (_: RuntimeException) {
            onDone(false)
        }
    }

    private companion object {
        /**
         * Strong biometrics or the screen lock from Android 11. On Android 10, androidx.biometric
         * rejects STRONG with the screen lock; weak-or-screen-lock is what the older
         * setDeviceCredentialAllowed(true) asked for, without the deprecated call.
         */
        val AUTHENTICATORS: Int = if (Build.VERSION.SDK_INT >= 30) {
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        } else {
            Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        }
    }
}
