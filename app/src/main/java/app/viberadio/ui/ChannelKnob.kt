package app.viberadio.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.viberadio.R
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The one control: a rotary dial with a detent per channel.
 *
 * The pointer follows the finger while dragging, ticks on each detent, and only commits the
 * channel on release, so sweeping across the dial doesn't retune five times.
 *
 * A play glyph sits in the centre while nothing is playing; tapping it calls [onPlay]. It
 * disappears once playback starts, so there is never a pause control on the song side.
 *
 * @param selected the tuned channel, or null before the first tune.
 * @param showPlay whether to show the centre play button (i.e. nothing is playing).
 */
@Composable
fun ChannelKnob(
    channelNames: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    showPlay: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = channelNames.size
    val angle = remember { Animatable(KnobMath.START) }
    var dragging by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(selected) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)
    val currentShowPlay by rememberUpdatedState(showPlay)
    val currentOnPlay by rememberUpdatedState(onPlay)

    LaunchedEffect(selected, count) {
        hovered = selected
        if (!dragging) {
            angle.animateTo(
                if (selected == null) KnobMath.START else KnobMath.detentAngle(selected, count),
                spring(stiffness = 300f),
            )
        }
    }

    fun commit(index: Int) {
        scope.launch { angle.animateTo(KnobMath.detentAngle(index, count), spring(stiffness = 300f)) }
        if (index != currentSelected) currentOnSelect(index)
    }

    val ink = MaterialTheme.colorScheme.onBackground
    val faint = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.knob_description)
    val playLabel = stringResource(R.string.play)
    val state = hovered?.let {
        stringResource(R.string.knob_state, channelNames.getOrElse(it) { "" }, it + 1, count)
    }.orEmpty()

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .semantics {
                contentDescription = description
                stateDescription = state
                if (showPlay) {
                    customActions = listOf(CustomAccessibilityAction(playLabel) { currentOnPlay(); true })
                }
                if (count > 1) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = (selected ?: 0).toFloat(),
                        range = 0f..(count - 1).toFloat(),
                        steps = (count - 2).coerceAtLeast(0),
                    )
                    setProgress { target ->
                        commit(target.roundToInt().coerceIn(0, count - 1))
                        true
                    }
                }
            }
            .pointerInput(count) {
                detectTapGestures { pos ->
                    if (count == 0) return@detectTapGestures
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val playRadius = size.width / 2f * DIAL_FRACTION * KnobMath.PLAY_HIT_FRACTION
                    if (currentShowPlay && KnobMath.isWithin(pos.x, pos.y, cx, cy, playRadius)) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        currentOnPlay()
                        return@detectTapGestures
                    }
                    val a = KnobMath.pointerAngle(pos.x, pos.y, cx, cy)
                    commit(KnobMath.nearestDetent(a, count))
                }
            }
            .pointerInput(count) {
                if (count == 0) return@pointerInput
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        commit(KnobMath.nearestDetent(angle.value, count))
                    },
                    onDragCancel = {
                        dragging = false
                        val tuned = currentSelected
                        hovered = tuned
                        scope.launch {
                            angle.animateTo(if (tuned == null) KnobMath.START else KnobMath.detentAngle(tuned, count))
                        }
                    },
                ) { change, _ ->
                    val a = KnobMath.pointerAngle(
                        change.position.x, change.position.y, size.width / 2f, size.height / 2f,
                    )
                    scope.launch { angle.snapTo(a) }
                    val detent = KnobMath.nearestDetent(a, count)
                    if (detent != hovered) {
                        hovered = detent
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                }
            },
    ) {
        val radius = size.minDimension / 2f
        val dialRadius = radius * DIAL_FRACTION
        val stroke = 2.dp.toPx()

        // Detent ticks around the dial; the tuned one is drawn in ink.
        for (i in 0 until count) {
            rotate(KnobMath.detentAngle(i, count)) {
                drawLine(
                    color = if (i == hovered) ink else faint,
                    start = Offset(center.x, center.y - radius * 0.86f),
                    end = Offset(center.x, center.y - radius * 0.97f),
                    strokeWidth = if (i == hovered) stroke * 1.5f else stroke,
                    cap = StrokeCap.Round,
                )
            }
        }

        // The dial body and its pointer.
        drawCircle(color = faint, radius = dialRadius, style = Stroke(width = stroke))
        rotate(angle.value) {
            drawLine(
                color = ink,
                start = Offset(center.x, center.y - dialRadius * 0.35f),
                end = Offset(center.x, center.y - dialRadius * 0.85f),
                strokeWidth = stroke * 2f,
                cap = StrokeCap.Round,
            )
        }

        // Centre play button, only while nothing plays. Clears the pointer, which starts at 0.35.
        if (showPlay) {
            val h = dialRadius * 0.2f
            // Nudged right so the triangle's centroid, not its bounding box, sits on the centre.
            val left = center.x - h * 0.58f
            val path = Path().apply {
                moveTo(left, center.y - h)
                lineTo(left + h * 1.75f, center.y)
                lineTo(left, center.y + h)
                close()
            }
            drawPath(path, color = ink)
        }
    }
}

/** Radius of the dial body as a fraction of half the knob's size. */
private const val DIAL_FRACTION = 0.72f
