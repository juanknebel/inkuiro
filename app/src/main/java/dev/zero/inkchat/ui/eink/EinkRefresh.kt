package dev.zero.inkchat.ui.eink

import android.os.Build
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * Facade over the Onyx SDK (onyxsdk-device). On non-Onyx devices (emulator)
 * everything is a no-op. Every call goes through runCatching: a firmware
 * difference must never bring the app down.
 */
object EinkRefresh {

    val isOnyxDevice: Boolean =
        Build.MANUFACTURER.equals("ONYX", ignoreCase = true) ||
            Build.BRAND.equals("ONYX", ignoreCase = true)

    /**
     * Fast mode (A2 class) during text generation or with the keyboard open:
     * quick partial refresh at the cost of quality.
     */
    fun applyFastMode(view: View) {
        if (!isOnyxDevice) return
        runCatching { EpdController.setViewDefaultUpdateMode(view, UpdateMode.ANIMATION_QUALITY) }
    }

    /** Returns the view to the system's default refresh mode (reading). */
    fun applyQualityMode(view: View) {
        if (!isOnyxDevice) return
        runCatching { EpdController.resetViewUpdateMode(view) }
    }

    /** Full refresh (GC): when a reply finishes and on screen changes, removes ghosting. */
    fun fullRefresh(view: View) {
        if (!isOnyxDevice) return
        runCatching { EpdController.invalidate(view, UpdateMode.GC) }
    }
}
