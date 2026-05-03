package com.jossephus.chuchu.ui.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class VerificationResult {
    Success,
    Failed,
    Unavailable,
}

fun requireUserVerification(
    context: Context,
    title: String,
    subtitle: String,
    onResult: (VerificationResult) -> Unit,
) {
    val activity = context.findFragmentActivity() ?: run {
        onResult(VerificationResult.Unavailable)
        return
    }
    val manager = BiometricManager.from(activity)
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val canAuthenticate = manager.canAuthenticate(authenticators)
    if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
        onResult(VerificationResult.Unavailable)
        return
    }
    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onResult(VerificationResult.Success)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onResult(VerificationResult.Failed)
        }

        override fun onAuthenticationFailed() {
            // Keep prompt open for retries; this callback is not a terminal failure.
        }
    }
    val prompt = BiometricPrompt(activity, executor, callback)
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(authenticators)
        .build()
    prompt.authenticate(promptInfo)
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
