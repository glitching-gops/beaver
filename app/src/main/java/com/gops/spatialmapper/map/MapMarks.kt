package com.gops.spatialmapper.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gops.spatialmapper.R

/**
 * Which overlay groups the Global map draws over the satellite basemap.
 *
 * Every flag is a DISPLAY decision only. Nothing here may feed back into Scout tracking, survey
 * recording, auto-follow or the camera: the raw map exists so the operator can compare marks with the
 * imagery under them, and it must not change what gets recorded while they look.
 *
 * The basemap, the map attribution, the banners and the targeting crosshair are not listed because
 * they are never hidden.
 */
data class GlobalMapLayers(
    /** Tree canopy polygons, plus the pin on a focused tree. */
    val canopies: Boolean,
    val clusterOutlines: Boolean,
    val removedTrees: Boolean,
    /** A saved survey track opened from History. */
    val surveyTrack: Boolean,
    val classLegend: Boolean,
    /** Scout's live position dot and breadcrumb trail. */
    val scoutPosition: Boolean,
    val toggleButton: Boolean
)

/**
 * The Global map's layers for one toggle state.
 *
 * [markingMode] overrides [marksShown]: an operator aiming at a removed tree has to see the trees
 * around it, so targeting always draws everything, and hides the toggle so it can't be switched off
 * mid-aim.
 */
fun globalMapLayers(marksShown: Boolean, markingMode: Boolean): GlobalMapLayers {
    val drawn = marksShown || markingMode
    return GlobalMapLayers(
        canopies = drawn,
        clusterOutlines = drawn,
        removedTrees = drawn,
        surveyTrack = drawn,
        classLegend = drawn,
        scoutPosition = drawn,
        toggleButton = !markingMode
    )
}

/** Which overlay groups the map review screen draws over the satellite basemap. */
data class ReviewMapLayers(
    val canopies: Boolean,
    val clusterOutline: Boolean,
    /** The azure "photo taken here" pin. */
    val originPin: Boolean,
    /** Every tree pin and radius handle: the things that can be dragged. */
    val dragHandles: Boolean
)

/**
 * The review map's layers for one toggle state. Hiding the handles pauses editing, which is the
 * point: a handle that cannot be seen must not be draggable either.
 */
fun reviewMapLayers(marksShown: Boolean): ReviewMapLayers = ReviewMapLayers(
    canopies = marksShown,
    clusterOutline = marksShown,
    originPin = marksShown,
    dragHandles = marksShown
)

/**
 * Floating eye button that switches a map between its marks and the raw imagery.
 *
 * The icon shows the CURRENT state (open eye = marks shown), the description says what a tap does.
 * 48 dp is the whole target, not just the glyph, so it is hittable with a thumb in the field.
 */
@Composable
fun MarksToggleButton(marksShown: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onToggle,
        modifier = modifier
            .size(48.dp)
            .semantics { stateDescription = if (marksShown) "Marks shown" else "Marks hidden" },
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.94f),
        shadowElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(
                    if (marksShown) R.drawable.ic_marks_shown else R.drawable.ic_marks_hidden
                ),
                contentDescription = if (marksShown) "Hide map marks" else "Show map marks",
                tint = Color.Black
            )
        }
    }
}
