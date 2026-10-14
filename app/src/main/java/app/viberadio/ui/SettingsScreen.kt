package app.viberadio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.viberadio.R
import app.viberadio.ui.theme.VibeRadioTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * The way in to settings: a small, faint gear in the top corner, the same on both pages.
 * Drawn in the rule colour like the idle thumbs, so it's there when you look for it and
 * otherwise reads as part of the page edge.
 */
@Composable
fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = SettingsIcons.Gear,
            contentDescription = stringResource(R.string.settings_open),
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The settings panel. A full page over the pager, closed by its back arrow or the system back
 * gesture. Empty for now: it exists so the way in and out can be tried before anything lives
 * here.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 12.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = SettingsIcons.Back,
                        contentDescription = stringResource(R.string.settings_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
            ) {
                Text(
                    stringResource(R.string.settings_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * Glyphs for settings, inlined like [ThumbIcons] so the app doesn't pull in an icon library.
 * The back arrow is Material Icons "ArrowBack" (Apache 2.0); the gear is generated: eight
 * square-ish teeth around a ring with a hole, filled even-odd.
 */
internal object SettingsIcons {
    val Back: ImageVector = ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"),
            fill = SolidColor(Color.Black),
        )
        .build()

    val Gear: ImageVector = ImageVector.Builder("Gear", 24.dp, 24.dp, 24f, 24f)
        .path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            val teeth = 8
            val step = 360.0 / teeth
            fun point(radius: Float, degrees: Double): Pair<Float, Float> {
                val rad = Math.toRadians(degrees - 90)
                return Pair(12f + radius * cos(rad).toFloat(), 12f + radius * sin(rad).toFloat())
            }
            for (i in 0 until teeth) {
                val centre = i * step
                // Root, tip, tip, root: a tooth slightly narrower at the tip than at its base.
                val outline = listOf(
                    point(ROOT, centre - 15),
                    point(TIP, centre - 9),
                    point(TIP, centre + 9),
                    point(ROOT, centre + 15),
                )
                outline.forEachIndexed { index, (x, y) ->
                    if (i == 0 && index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            close()
            // The hole: a full circle as two half arcs.
            moveTo(12f - HOLE, 12f)
            arcTo(HOLE, HOLE, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f + HOLE, y1 = 12f)
            arcTo(HOLE, HOLE, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f - HOLE, y1 = 12f)
            close()
        }
        .build()

    private const val TIP = 10.5f
    private const val ROOT = 8f
    private const val HOLE = 3.2f
}

@Preview(name = "Settings light", showBackground = true)
@Composable
private fun SettingsLightPreview() {
    VibeRadioTheme(darkTheme = false) { SettingsScreen(onClose = {}) }
}

@Preview(name = "Settings dark", showBackground = true)
@Composable
private fun SettingsDarkPreview() {
    VibeRadioTheme(darkTheme = true) { SettingsScreen(onClose = {}) }
}

@Preview(name = "Gear", showBackground = true)
@Composable
private fun SettingsButtonPreview() {
    VibeRadioTheme(darkTheme = false) { SettingsButton(onClick = {}) }
}
