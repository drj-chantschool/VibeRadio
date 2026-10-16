package app.viberadio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.viberadio.playback.audio.ToneBands
import app.viberadio.playback.audio.ToneCurve
import kotlin.math.roundToInt

/** Slider geometry for the graphic EQ: pure, so it's tested on the JVM. */
internal object EqSliderMath {
    /** Drags land on tenths of a dB. */
    const val STEPS_PER_DB = 10

    /** Where [db] sits on the slider: 0 at the bottom of [range], 1 at the top. */
    fun fraction(db: Float, range: ClosedFloatingPointRange<Float>): Float =
        ((db - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

    /**
     * The value after dragging [dyPx] (down is positive, as on screen) from [startDb], on a
     * slider whose whole [range] spans [travelPx]. Snapped and kept in range.
     */
    fun dragged(startDb: Float, dyPx: Float, travelPx: Float, range: ClosedFloatingPointRange<Float>): Float {
        if (travelPx <= 0f) return snap(startDb, range)
        return snap(startDb - dyPx / travelPx * (range.endInclusive - range.start), range)
    }

    fun snap(db: Float, range: ClosedFloatingPointRange<Float>): Float =
        // + 0f turns −0 into 0, so a band dragged back to the middle reads as flat.
        ((db * STEPS_PER_DB).roundToInt().toFloat() / STEPS_PER_DB).coerceIn(range.start, range.endInclusive) + 0f
}

/**
 * One stream's tone curve as a row of vertical sliders, one per band, scrolling sideways when
 * the screen is too narrow for all ten. Drag a band up or down (tenths of a dB, a tick at
 * 0 dB), double-tap it back to flat, or tap its figure to type a value ([onEdit]).
 */
@Composable
internal fun GraphicEqSliders(
    curve: ToneCurve,
    onBand: (band: Int, db: Float) -> Unit,
    onEdit: (band: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.Top,
    ) {
        Row(Modifier.padding(PaddingValues(horizontal = 12.dp))) {
            for (band in 0 until ToneBands.COUNT) {
                BandSlider(
                    band = band,
                    db = curve.bandsDb[band],
                    onChange = { onBand(band, it) },
                    onEdit = { onEdit(band) },
                )
            }
        }
    }
}

@Composable
private fun BandSlider(band: Int, db: Float, onChange: (Float) -> Unit, onEdit: () -> Unit) {
    val range = ToneCurve.RANGE
    val current by rememberUpdatedState(db)
    val change by rememberUpdatedState(onChange)
    val haptics = LocalHapticFeedback.current

    val ink = MaterialTheme.colorScheme.onBackground
    val track = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.outline
    val knob = MaterialTheme.colorScheme.surfaceVariant
    val bandName = EqStrings.band(band)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(SLOT_WIDTH)) {
        Canvas(
            Modifier
                .width(SLOT_WIDTH)
                .height(SLIDER_HEIGHT)
                .semantics {
                    contentDescription = bandName
                    progressBarRangeInfo = ProgressBarRangeInfo(db, range, steps = 0)
                    setProgress { target ->
                        change(EqSliderMath.snap(target, range))
                        true
                    }
                }
                .pointerInput(band) {
                    detectTapGestures(onDoubleTap = { if (current != 0f) change(0f) })
                }
                .pointerInput(band) {
                    var start = 0f
                    var dy = 0f
                    var last = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            start = current
                            last = current
                            dy = 0f
                        },
                    ) { pointer, amount ->
                        pointer.consume()
                        dy += amount
                        val travel = size.height - THUMB_HEIGHT.toPx()
                        val next = EqSliderMath.dragged(start, dy, travel, range)
                        if (next != last) {
                            if (next == 0f || (last > 0f && next < 0f) || (last < 0f && next > 0f)) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            last = next
                            change(next)
                        }
                    }
                },
        ) {
            val thumbH = THUMB_HEIGHT.toPx()
            val thumbW = THUMB_WIDTH.toPx()
            val travel = size.height - thumbH
            fun y(value: Float) = thumbH / 2 + (1f - EqSliderMath.fraction(value, range)) * travel
            val x = size.width / 2
            val line = 2.dp.toPx()

            // Ticks at +12, 0 and −12 dB, either side of the track.
            val tickLen = 6.dp.toPx()
            val tickGap = thumbW / 2 + 4.dp.toPx()
            for (mark in listOf(range.endInclusive, 0f, range.start)) {
                val ty = y(mark)
                drawLine(tick, Offset(x - tickGap - tickLen, ty), Offset(x - tickGap, ty), line)
                drawLine(tick, Offset(x + tickGap, ty), Offset(x + tickGap + tickLen, ty), line)
            }

            drawLine(track, Offset(x, y(range.endInclusive)), Offset(x, y(range.start)), line, StrokeCap.Round)
            val thumbY = y(db)
            drawLine(ink, Offset(x, y(0f)), Offset(x, thumbY), 4.dp.toPx(), StrokeCap.Round)

            val corner = CornerRadius(thumbW / 2, thumbW / 2)
            val topLeft = Offset(x - thumbW / 2, thumbY - thumbH / 2)
            drawRoundRect(knob, topLeft, Size(thumbW, thumbH), corner)
            drawRoundRect(tick, topLeft, Size(thumbW, thumbH), corner, style = Stroke(line))
            val grip = thumbW * 0.22f
            drawLine(ink, Offset(x - grip, thumbY), Offset(x + grip, thumbY), 3.dp.toPx(), StrokeCap.Round)
        }
        Text(
            ToneBands.LABELS[band],
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            EqStrings.db(db.toDouble()),
            style = MaterialTheme.typography.bodyMedium,
            color = if (db == 0f) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clickable(onClick = onEdit)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

private val SLOT_WIDTH = 56.dp
private val SLIDER_HEIGHT = 240.dp
private val THUMB_WIDTH = 32.dp
private val THUMB_HEIGHT = 52.dp
