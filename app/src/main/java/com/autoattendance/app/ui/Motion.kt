package com.autoattendance.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.autoattendance.app.ui.theme.LocalPalette

// ---- Tokens ---------------------------------------------------------------------------------

/** One place for every duration and easing, so the whole app moves the same way. */
object Motion {
    /** Micro-interactions: colour changes, press feedback, small fades. */
    const val SHORT = 150
    /** Selection changes, expanding notes, dialog content. */
    const val MEDIUM = 250
    /** Screen-level transitions. */
    const val LONG = 350

    /** Material 3 "emphasized decelerate" – fast start, gentle stop. Used for things entering. */
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Material 3 "emphasized accelerate". Used for things leaving. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard: Easing = FastOutSlowInEasing

    fun <T> tween(reduce: Boolean, duration: Int = MEDIUM, easing: Easing = Standard, delay: Int = 0): FiniteAnimationSpec<T> =
        if (reduce) snap() else androidx.compose.animation.core.tween(duration, delay, easing)

    /** Critically damped spring: natural, no bounce or overshoot. */
    fun <T> spring(reduce: Boolean, stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
        if (reduce) snap() else androidx.compose.animation.core.spring(Spring.DampingRatioNoBouncy, stiffness)
}

/**
 * True when the user turned animations off (Developer options › Animator duration scale = off,
 * or Accessibility › Remove animations). Every animation in the app then snaps instead.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

fun reduceMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun <T> motionTween(duration: Int = Motion.MEDIUM, easing: Easing = Motion.Standard): FiniteAnimationSpec<T> =
    Motion.tween(LocalReduceMotion.current, duration, easing)

@Composable
fun <T> motionSpring(stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
    Motion.spring(LocalReduceMotion.current, stiffness)

/** Colour that fades to its new value instead of jumping. */
@Composable
fun animatedColor(target: Color, label: String = "color"): Color =
    animateColorAsState(target, motionTween(Motion.SHORT), label = label).value

// ---- Interaction ----------------------------------------------------------------------------

/**
 * Shrinks the element slightly while a finger is on it. It only observes touches (never consumes
 * them), so the element's own click still fires immediately; dragging past the touch slop releases it.
 */
fun Modifier.pressFeedback(enabled: Boolean = true, pressedScale: Float = 0.96f): Modifier = composed {
    val reduce = LocalReduceMotion.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed && enabled && !reduce) pressedScale else 1f,
        Motion.spring(reduce, Spring.StiffnessMedium),
        label = "press",
    )
    this
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || (change.position - down.position).getDistance() > slop) break
                }
                pressed = false
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

enum class ButtonKind { Filled, Tonal, Outlined, Text }

/**
 * The app's button. Colours fade between enabled and disabled, it shrinks slightly when pressed,
 * and the click runs on the first tap – no animation ever delays the action.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Filled,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val p = LocalPalette.current
    val container = animatedColor(
        when {
            kind == ButtonKind.Outlined || kind == ButtonKind.Text -> Color.Transparent
            !enabled -> p.disabledBg
            kind == ButtonKind.Filled -> p.primary
            else -> p.primarySoft
        },
    )
    val content = animatedColor(
        when {
            !enabled -> p.disabledText
            kind == ButtonKind.Filled -> p.onPrimary
            kind == ButtonKind.Tonal -> p.text
            else -> p.primary
        },
    )
    val border = animatedColor(if (enabled) p.primarySoft else p.disabledBg)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = if (kind == ButtonKind.Outlined) BorderStroke(1.dp, border) else null,
        modifier = modifier
            .pressFeedback(enabled)
            .semantics { role = Role.Button },
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = if (kind == ButtonKind.Text) 12.dp else 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            icon?.let {
                Icon(it, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
    }
}

/** Two-or-more option toggle whose highlight slides to the selected option. */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val p = LocalPalette.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(FieldShape)
            .background(p.primarySofter)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        val indicatorX by animateDpAsState(segment * selected, motionSpring(Spring.StiffnessMedium), label = "segment")
        Box(
            Modifier
                // Lambda offset: the highlight moves in the layout phase, without recomposing every frame.
                .offset { IntOffset(indicatorX.roundToPx(), 0) }
                .width(segment)
                .height(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(p.primarySoft),
        )
        Row(Modifier.height(40.dp)) {
            options.forEachIndexed { i, label ->
                val sel = i == selected
                val color = animatedColor(if (sel) p.primary else p.textMuted)
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(11.dp))
                        .clickable(role = Role.Tab) { if (!sel) onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon?.let {
                        Icon(it, null, tint = color, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = color,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ---- Appearing content ----------------------------------------------------------------------

/** Standard way for notes, banners and sections to appear: fade + grow, never a sudden jump. */
@Composable
fun ExpandFade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(Motion.tween(reduce, Motion.MEDIUM)) + expandVertically(Motion.tween(reduce, Motion.MEDIUM, Motion.Enter)),
        exit = fadeOut(Motion.tween(reduce, Motion.SHORT)) + shrinkVertically(Motion.tween(reduce, Motion.MEDIUM, Motion.Exit)),
        content = content,
    )
}

/** Cross-fade (with a slight scale) between values such as a status or a label. */
@Composable
fun <T> FadeSwap(
    target: T,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable (T) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        contentAlignment = contentAlignment,
        transitionSpec = {
            (fadeIn(Motion.tween(reduce, Motion.MEDIUM, delay = if (reduce) 0 else 60)) +
                scaleIn(Motion.tween(reduce, Motion.MEDIUM, Motion.Enter), initialScale = 0.92f)) togetherWith
                fadeOut(Motion.tween(reduce, Motion.SHORT)) using SizeTransform(clip = false) { _, _ -> Motion.tween(reduce, Motion.MEDIUM) }
        },
        label = "swap",
    ) { content(it) }
}

/** Amber banner with a spinner while a run is in progress; slides in and out smoothly. */
@Composable
fun ProgressBanner(progress: String?) {
    val p = LocalPalette.current
    // Keep showing the last text while the banner animates away (plain holder: not observed state).
    val last = remember { arrayOf("") }
    if (progress != null) last[0] = progress
    ExpandFade(progress != null) {
        Surface(shape = FieldShape, color = p.amberSoft, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = p.amber, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(progress ?: last[0], color = p.amber, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---- Dialogs --------------------------------------------------------------------------------

/** Handed to dialog content; closing always animates out before the dialog is removed. */
@Stable
class DialogController internal constructor(private val state: MutableTransitionState<Boolean>) {
    var closing by mutableStateOf(false)
        private set

    /**
     * Runs [action] immediately (so nothing waits for the animation), then animates the dialog out.
     * Further calls while closing are ignored, so a double tap can never run an action twice.
     */
    fun close(action: () -> Unit = {}) {
        if (closing) return
        closing = true
        action()
        state.targetState = false
    }
}

/**
 * Base for every dialog: the background dims in, the content fades and scales up from 92 %, and both
 * reverse on dismissal (Back, tapping outside, or [DialogController.close]). [onDismissed] runs after
 * the exit animation – that is where the caller stops showing the dialog.
 */
@Composable
fun AppDialog(
    onDismissed: () -> Unit,
    dismissOnOutside: Boolean = true,
    maxWidth: Int = 560,
    content: @Composable (DialogController) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val controller = remember { DialogController(state) }
    if (!state.targetState && state.isIdle && !state.currentState) {
        LaunchedEffect(Unit) { onDismissed() }
    }

    Dialog(
        onDismissRequest = { controller.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Replace the window's own instant dim and pop-in with the animated scrim below.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visibleState = state,
                enter = fadeIn(Motion.tween(reduce, Motion.MEDIUM)),
                exit = fadeOut(Motion.tween(reduce, Motion.MEDIUM)),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = dismissOnOutside,
                        ) { controller.close() },
                )
            }
            AnimatedVisibility(
                visibleState = state,
                enter = fadeIn(Motion.tween(reduce, Motion.MEDIUM, Motion.Enter)) +
                    scaleIn(Motion.tween(reduce, Motion.MEDIUM, Motion.Enter), initialScale = 0.92f),
                exit = fadeOut(Motion.tween(reduce, Motion.SHORT, Motion.Exit)) +
                    scaleOut(Motion.tween(reduce, Motion.SHORT, Motion.Exit), targetScale = 0.95f),
                modifier = Modifier
                    .safeDrawingPadding()
                    .imePadding()
                    .padding(16.dp)
                    .widthIn(max = maxWidth.dp),
            ) {
                content(controller)
            }
        }
    }
}

/** Titled dialog with buttons at the bottom, built on [AppDialog]. */
@Composable
fun AppAlertDialog(
    onDismissed: () -> Unit,
    title: String,
    buttons: @Composable RowScope.(DialogController) -> Unit,
    content: @Composable ColumnScope.(DialogController) -> Unit,
) {
    val p = LocalPalette.current
    AppDialog(onDismissed) { c ->
        Surface(shape = CardShape, color = p.card) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = p.text)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content(c) }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) { buttons(c) }
            }
        }
    }
}
