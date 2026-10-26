package com.autoattendance.app

import android.app.Application
import com.autoattendance.app.feedback.Notifier

class AutoAttendanceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
