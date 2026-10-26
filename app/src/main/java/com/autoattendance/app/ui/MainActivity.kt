package com.autoattendance.app.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.autoattendance.app.schedule.AlarmScheduler
import com.autoattendance.app.ui.theme.AppTheme
import com.autoattendance.app.ui.theme.LocalPalette

class MainActivity : ComponentActivity() {

    /** Bumped on every resume so permission checks refresh after returning from Settings. */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark status/navigation bar icons on the white theme, even in system dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
                BackHandler(enabled = tab != AppTab.HOME) { tab = AppTab.HOME }
                Scaffold(
                    containerColor = LocalPalette.current.background,
                    bottomBar = { BottomBar(tab) { tab = it } },
                ) { pad ->
                    val tick = resumeTick.intValue
                    // Each tab keeps its scroll position and UI state while you are on another tab.
                    val saved = rememberSaveableStateHolder()
                    val reduce = LocalReduceMotion.current
                    AnimatedContent(
                        targetState = tab,
                        modifier = Modifier.fillMaxSize().padding(pad),
                        transitionSpec = { tabTransition(initialState, targetState, reduce) },
                        label = "tab",
                    ) { shown ->
                        saved.SaveableStateProvider(shown.name) {
                            val m = Modifier.fillMaxSize()
                            when (shown) {
                                AppTab.HOME -> HomeScreen(
                                    tick, m,
                                    onOpenSchedule = { tab = AppTab.SCHEDULE },
                                    onOpenSettings = { tab = AppTab.SETTINGS },
                                )
                                AppTab.SCHEDULE -> ScheduleScreen(tick, m)
                                AppTab.LOGS -> LogsScreen(m)
                                AppTab.SETTINGS -> SettingsScreen(tick, m)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        AlarmScheduler.reschedule(this)
    }
}

/**
 * Tabs slide a short distance in the direction of travel (right for later tabs, left for earlier
 * ones and Back) while cross-fading – enough to show where you went, without big movement.
 */
private fun tabTransition(from: AppTab, to: AppTab, reduce: Boolean): ContentTransform {
    val dir = if (to.ordinal > from.ordinal) 1 else -1
    // Material "fade through": the old screen is gone after 90 ms, then the new one fades in and slides,
    // so the two never visibly overlap.
    return (
        slideInHorizontally(Motion.tween(reduce, Motion.LONG, Motion.Enter)) { w -> dir * w / 12 } +
            fadeIn(Motion.tween(reduce, 210, LinearEasing, delay = if (reduce) 0 else 90))
        ) togetherWith (
        slideOutHorizontally(Motion.tween(reduce, Motion.MEDIUM, Motion.Exit)) { w -> -dir * w / 12 } +
            fadeOut(Motion.tween(reduce, 90, LinearEasing))
        )
}
