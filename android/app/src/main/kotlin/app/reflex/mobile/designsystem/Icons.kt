package app.reflex.mobile.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small vector set; icons never use text glyphs or require a font dependency. */
object HealthIcons {
    private fun icon(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
    ).apply { path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, pathBuilder = draw) }.build()

    val Plus = icon("Add") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Down = icon("Expand") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    val Back = icon("Previous") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
    val Next = icon("Next") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    val Check = icon("Taken") { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 7f) }
    val Minus = icon("Skipped") { moveTo(5f, 12f); lineTo(19f, 12f) }
    val Warning = icon("Missed") { moveTo(12f, 4f); lineTo(12f, 14f); moveTo(12f, 18f); lineTo(12f, 20f) }
    val Dot = ImageVector.Builder("Due", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 6f); curveTo(20f, 6f, 20f, 18f, 12f, 18f)
            curveTo(4f, 18f, 4f, 6f, 12f, 6f); close()
        }
    }.build()
    val Clock = icon("Upcoming") {
        moveTo(12f, 3f); curveTo(24f, 3f, 24f, 21f, 12f, 21f)
        curveTo(0f, 21f, 0f, 3f, 12f, 3f); close()
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
    }
    val Bell = icon("Reminders") {
        moveTo(5f, 17f); lineTo(7f, 14f); lineTo(7f, 8f)
        curveTo(7f, 1f, 17f, 1f, 17f, 8f); lineTo(17f, 14f); lineTo(19f, 17f); close()
        moveTo(10f, 20f); curveTo(10f, 23f, 14f, 23f, 14f, 20f)
    }

    private fun svg(name: String, d: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
    ).addPath(pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f).build()

    // Tab bar: Today = checklist, Medications = pill (a capsule), History = calendar.
    val Checklist = svg("Today", "M10 6h10M10 12h10M10 18h10M3.5 6l1.5 1.5L7.5 5M3.5 12l1.5 1.5 2.5-2.5M3.5 18l1.5 1.5 2.5-2.5")
    /** A capsule at 45° with its mid-line (never a circle with a slash). */
    val Pill = svg("Medications",
        "M4.525 14.525 14.525 4.525A3.5 3.5 0 0 1 19.475 9.475L9.475 19.475A3.5 3.5 0 0 1 4.525 14.525ZM9.525 9.525l4.95 4.95")
    val Calendar = svg("History", "M4 5h16v16H4Zm4-2v4m8-4v4M4 11h16")
    /** Trailing affordance of a row that opens a menu. */
    val More = ImageVector.Builder("More actions", 24.dp, 24.dp, 24f, 24f).apply {
        for (x in listOf(5f, 12f, 19f)) path(fill = SolidColor(Color.Black)) {
            moveTo(x, 10.25f); curveTo(x + 2.33f, 10.25f, x + 2.33f, 13.75f, x, 13.75f)
            curveTo(x - 2.33f, 13.75f, x - 2.33f, 10.25f, x, 10.25f); close()
        }
    }.build()
    val Close = icon("Remove") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
}
