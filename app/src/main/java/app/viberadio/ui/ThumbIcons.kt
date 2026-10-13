package app.viberadio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Thumb glyphs for song feedback (#13). Path data from Material Icons "ThumbUp"/"ThumbDown"
 * (Apache 2.0), inlined so the app doesn't pull in an icon library for two shapes. Tinted by
 * the caller from the e-ink palette.
 */
internal object ThumbIcons {
    val Up: ImageVector = icon(
        "ThumbUp",
        "M1,21h4L5,9L1,9v12zM23,10c0,-1.1 -0.9,-2 -2,-2h-6.31l0.95,-4.57 0.03,-0.32c0,-0.41 -0.17,-0.79 " +
            "-0.44,-1.06L14.17,1 7.59,7.59C7.22,7.95 7,8.45 7,9v10c0,1.1 0.9,2 2,2h9c0.83,0 1.54,-0.5 " +
            "1.84,-1.22l3.02,-7.05c0.09,-0.23 0.14,-0.47 0.14,-0.73v-2z",
    )

    val Down: ImageVector = icon(
        "ThumbDown",
        "M15,3L6,3c-0.83,0 -1.54,0.5 -1.84,1.22l-3.02,7.05c-0.09,0.23 -0.14,0.47 -0.14,0.73v2c0,1.1 " +
            "0.9,2 2,2h6.31l-0.95,4.57 -0.03,0.32c0,0.41 0.17,0.79 0.44,1.06L9.83,23l6.59,-6.59c0.36,-0.36 " +
            "0.58,-0.86 0.58,-1.41L17,5c0,-1.1 -0.9,-2 -2,-2zM19,3v12h4L23,3h-4z",
    )

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
