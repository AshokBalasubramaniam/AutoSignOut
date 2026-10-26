package com.autoattendance.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autoattendance.app.data.Operation
import com.autoattendance.app.data.OperationSchedule
import com.autoattendance.app.data.Repository
import com.autoattendance.app.schedule.AlarmScheduler

/** Schedule tab: time, recurrence, dates and the next runs for Sign In and Sign Out. */
@Composable
fun ScheduleScreen(resumeTick: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val repo = remember { Repository.get(ctx) }
    val settings by repo.settings.collectAsState()

    fun updateSchedule(op: Operation, transform: (OperationSchedule) -> OperationSchedule) {
        repo.updateSchedule(op, transform)
        AlarmScheduler.reschedule(ctx)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader("Schedule", "Time, location and dates for each operation")
        Operation.entries.forEach { op -> ScheduleCard(op, settings, resumeTick, ::updateSchedule) }
    }
}
