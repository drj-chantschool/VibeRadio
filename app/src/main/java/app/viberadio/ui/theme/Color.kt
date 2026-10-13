package app.viberadio.ui.theme

import androidx.compose.ui.graphics.Color

// E-ink palette: warm greys only. No accent hue anywhere, and the dark variant stays below
// full white so the screen never glares. Keep res/values/colors.xml in step with Paper/Ink.

// Light: printed page.
internal val Paper = Color(0xFFE9E7E1)
internal val PaperShade = Color(0xFFDEDBD4)
internal val Ink = Color(0xFF2E2D2B)
internal val InkSoft = Color(0xFF5A5955)
internal val InkFaint = Color(0xFF6E6C67)
internal val Rule = Color(0xFFB3B0A8)
internal val RuleFaint = Color(0xFFCFCCC5)
internal val ErrorInk = Color(0xFF7A4A44)

// Dark: page under a dim reading light.
internal val NightPaper = Color(0xFF1B1B1A)
internal val NightPaperShade = Color(0xFF252524)
internal val NightInk = Color(0xFFBDBAB3)
internal val NightInkSoft = Color(0xFF9D9A94)
internal val NightInkFaint = Color(0xFF8A8781)
internal val NightRule = Color(0xFF4A4946)
internal val NightRuleFaint = Color(0xFF333230)
internal val NightErrorInk = Color(0xFFB08A84)
