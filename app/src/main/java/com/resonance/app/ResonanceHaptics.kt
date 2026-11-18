package com.resonance.app

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

internal fun View.resonanceHaptic(confirmation: Boolean = false) {
    if (!isShown || !hasWindowFocus() || !isHapticFeedbackEnabled) return
    val effect = if (confirmation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else HapticFeedbackConstants.CLOCK_TICK
    // The platform applies the user's touch feedback setting when no override flag is used.
    performHapticFeedback(effect)
}
