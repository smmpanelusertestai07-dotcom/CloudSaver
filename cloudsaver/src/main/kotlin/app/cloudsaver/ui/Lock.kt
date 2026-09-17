package app.cloudsaver.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * App lock over the device's own credentials, and nothing softer.
 *
 * Two rules, learned from this lock having shipped broken once:
 *
 *  - **Fail closed.** The old code answered "unlocked" whenever
 *    `canAuthenticate` was not SUCCESS - and on API 29 the combined
 *    biometric-or-credential query misreports on many phones, so the lock
 *    silently opened exactly where it was supposed to hold. A gate that
 *    cannot verify stays shut and says why; it never waves someone through.
 *  - **The device credential is the root of trust.** Android already wipes
 *    biometric enrolment when the screen lock is removed, and removing the
 *    screen lock itself requires knowing it. So when [deviceSecure] turns
 *    false, the owner has chosen an unlocked phone; the app lock is disabled
 *    with a visible notice rather than becoming a permanent brick - the same
 *    stance the mainstream app-lock implementations take.
 */
object Lock {

    /**
     * STRONG|DEVICE_CREDENTIAL is rejected below API 30 by androidx.biometric
     * itself, so the set is chosen per SDK rather than hoping.
     */
    private val AUTHENTICATORS: Int = if (Build.VERSION.SDK_INT >= 30) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    /** True while the phone itself has a PIN, pattern, password or biometric. */
    fun deviceSecure(context: Context): Boolean = try {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        km.isDeviceSecure
    } catch (e: Exception) {
        false
    }

    /**
     * Whether the lock can be turned on right now.
     *
     * Used at enable time: a lock that cannot be verified must not be
     * enabled, because the alternative is enabling a door with no key. The
     * keyguard check is the authority; `canAuthenticate` alone misreports the
     * credential path on API 29.
     */
    fun canEnable(context: Context): Boolean = deviceSecure(context)

    sealed class Outcome {
        /** Identity confirmed. */
        data object Unlocked : Outcome()

        /** Cancelled or failed; the gate stays shut, the button stays. */
        data object Denied : Outcome()

        /** Too many attempts; Android is enforcing a cooldown. */
        data object LockedOut : Outcome()

        /** The phone has no screen lock at all - nothing can verify anyone. */
        data object NoMethod : Outcome()
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onResult: (Outcome) -> Unit
    ) {
        // No device credential means there is nothing to check against. The
        // caller decides what that means (App.kt disables the lock with a
        // notice); this function never invents an unlock.
        if (!deviceSecure(activity)) {
            onResult(Outcome.NoMethod)
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    onResult(Outcome.Unlocked)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(
                        when (errorCode) {
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> Outcome.LockedOut
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> Outcome.NoMethod
                            else -> Outcome.Denied
                        }
                    )
                }
                // onAuthenticationFailed (a wrong finger) keeps the system
                // sheet up for another try; it is not a terminal result.
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            // With DEVICE_CREDENTIAL in the set, the system supplies the
            // fallback button itself; confirmation-required stays default.
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            // A prompt that cannot even start is a denial, never a pass.
            onResult(Outcome.Denied)
        }
    }
}
