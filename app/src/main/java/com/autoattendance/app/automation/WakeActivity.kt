package com.autoattendance.app.automation

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Invisible activity that turns the screen on and asks Android to show its normal unlock prompt.
 * With a PIN/pattern/biometric lock the user must authenticate – nothing is bypassed.
 * With no secure lock (swipe/none) the keyguard is dismissed as the system allows.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            unlocked()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = unlocked()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    private fun unlocked() {
        AttendanceAccessibilityService.instance?.runner?.onUserPresent()
        finish()
    }
}
