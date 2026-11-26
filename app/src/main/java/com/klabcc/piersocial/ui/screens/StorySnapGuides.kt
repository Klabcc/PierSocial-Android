package com.klabcc.piersocial.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.SignalAmber
import kotlin.math.abs

enum class SnapGuideKind {
    CENTER,
    SAFE_MARGIN,
    ALIGN_PEER
}

data class ActiveSnapGuide(
    val position: Float,
    val kind: SnapGuideKind
)

data class StorySnapState(
    val isDragging: Boolean = false,
    val draggingStickerId: String? = null,
    val verticalGuide: ActiveSnapGuide? = null,
    val horizontalGuide: ActiveSnapGuide? = null
)

data class StorySnapResult(
    val snappedX: Float,
    val snappedY: Float,
    val verticalGuide: ActiveSnapGuide?,
    val horizontalGuide: ActiveSnapGuide?
)

object StorySnapEngine {
    const val CENTER_X = 0.5f
    const val CENTER_Y = 0.5f

    // Normalized Story 9:16 safe-zone margins (clears progress/header bar & bottom controls)
    const val SAFE_LEFT = 0.08f
    const val SAFE_RIGHT = 0.92f
    const val SAFE_TOP = 0.14f
    const val SAFE_BOTTOM = 0.84f

    const val SNAP_THRESHOLD_X = 0.026f
    const val SNAP_THRESHOLD_Y = 0.020f
    const val PHOTO_PAN_SNAP_THRESHOLD = 0.025f

    private data class SnapCandidate(
        val targetCenter: Float,
        val guideLine: Float,
        val kind: SnapGuideKind
    )

    fun snapSticker(
        rawX: Float,
        rawY: Float,
        halfWidthNorm: Float = 0f,
        halfHeightNorm: Float = 0f,
        peerXs: List<Float> = emptyList(),
        peerYs: List<Float> = emptyList(),
        enabled: Boolean = true,
        minX: Float = 0.06f,
        maxX: Float = 0.94f,
        minY: Float = 0.06f,
        maxY: Float = 0.94f
    ): StorySnapResult {
        val clampedX = rawX.coerceIn(minX, maxX)
        val clampedY = rawY.coerceIn(minY, maxY)
        if (!enabled) {
            return StorySnapResult(
                snappedX = clampedX,
                snappedY = clampedY,
                verticalGuide = null,
                horizontalGuide = null
            )
        }

        val safeHalfW = halfWidthNorm.coerceIn(0f, 0.36f)
        val safeHalfH = halfHeightNorm.coerceIn(0f, 0.25f)

        val xCandidates = buildList {
            add(SnapCandidate(CENTER_X, CENTER_X, SnapGuideKind.CENTER))
            peerXs.forEach { px ->
                add(SnapCandidate(px, px, SnapGuideKind.ALIGN_PEER))
            }
            add(SnapCandidate((SAFE_LEFT + safeHalfW).coerceIn(minX, maxX), SAFE_LEFT, SnapGuideKind.SAFE_MARGIN))
            add(SnapCandidate((SAFE_RIGHT - safeHalfW).coerceIn(minX, maxX), SAFE_RIGHT, SnapGuideKind.SAFE_MARGIN))
        }

        val yCandidates = buildList {
            add(SnapCandidate(CENTER_Y, CENTER_Y, SnapGuideKind.CENTER))
            peerYs.forEach { py ->
                add(SnapCandidate(py, py, SnapGuideKind.ALIGN_PEER))
            }
            add(SnapCandidate((SAFE_TOP + safeHalfH).coerceIn(minY, maxY), SAFE_TOP, SnapGuideKind.SAFE_MARGIN))
            add(SnapCandidate((SAFE_BOTTOM - safeHalfH).coerceIn(minY, maxY), SAFE_BOTTOM, SnapGuideKind.SAFE_MARGIN))
        }

        val bestX = xCandidates
            .map { candidate -> candidate to abs(clampedX - candidate.targetCenter) }
            .filter { (_, distance) -> distance <= SNAP_THRESHOLD_X }
            .minWithOrNull(compareBy({ it.second }, { it.first.kind.ordinal }))
            ?.first

        val bestY = yCandidates
            .map { candidate -> candidate to abs(clampedY - candidate.targetCenter) }
            .filter { (_, distance) -> distance <= SNAP_THRESHOLD_Y }
            .minWithOrNull(compareBy({ it.second }, { it.first.kind.ordinal }))
            ?.first

        return StorySnapResult(
            snappedX = (bestX?.targetCenter ?: clampedX).coerceIn(minX, maxX),
            snappedY = (bestY?.targetCenter ?: clampedY).coerceIn(minY, maxY),
            verticalGuide = bestX?.let { ActiveSnapGuide(it.guideLine, it.kind) },
            horizontalGuide = bestY?.let { ActiveSnapGuide(it.guideLine, it.kind) }
        )
    }

    fun snapPhotoPan(
        rawPanX: Float,
        rawPanY: Float,
        enabled: Boolean = true
    ): StorySnapResult {
        val clampedX = rawPanX.coerceIn(-0.45f, 0.45f)
        val clampedY = rawPanY.coerceIn(-0.45f, 0.45f)
        if (!enabled) {
            return StorySnapResult(clampedX, clampedY, null, null)
        }
        val snapX = abs(clampedX) <= PHOTO_PAN_SNAP_THRESHOLD
        val snapY = abs(clampedY) <= PHOTO_PAN_SNAP_THRESHOLD
        return StorySnapResult(
            snappedX = if (snapX) 0f else clampedX,
            snappedY = if (snapY) 0f else clampedY,
            verticalGuide = if (snapX) ActiveSnapGuide(CENTER_X, SnapGuideKind.CENTER) else null,
            horizontalGuide = if (snapY) ActiveSnapGuide(CENTER_Y, SnapGuideKind.CENTER) else null
        )
    }
}

@Composable
fun StorySnapGuidesOverlay(
    snapState: StorySnapState,
    modifier: Modifier = Modifier
) {
    if (!snapState.isDragging) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Subtle dashed Story safe-zone outline while positioning elements
        val safeLeftPx = StorySnapEngine.SAFE_LEFT * w
        val safeRightPx = StorySnapEngine.SAFE_RIGHT * w
        val safeTopPx = StorySnapEngine.SAFE_TOP * h
        val safeBottomPx = StorySnapEngine.SAFE_BOTTOM * h
        val dashPx = 6.dp.toPx()
        val gapPx = 5.dp.toPx()

        drawRoundRect(
            color = Color.White.copy(alpha = 0.22f),
            topLeft = Offset(safeLeftPx, safeTopPx),
            size = Size(safeRightPx - safeLeftPx, safeBottomPx - safeTopPx),
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), 0f)
            )
        )

        val lineWidthPx = 1.5.dp.toPx()
        val glowWidthPx = 4.dp.toPx()
        val peerDashEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()), 0f)

        fun guideColor(kind: SnapGuideKind): Color = when (kind) {
            SnapGuideKind.CENTER -> MeshCyan
            SnapGuideKind.SAFE_MARGIN -> SignalAmber
            SnapGuideKind.ALIGN_PEER -> Color.White
        }

        val vGuide = snapState.verticalGuide
        if (vGuide != null) {
            val x = vGuide.position * w
            val color = guideColor(vGuide.kind)
            drawLine(
                color = color.copy(alpha = 0.28f),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = glowWidthPx
            )
            drawLine(
                color = color,
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = lineWidthPx,
                pathEffect = if (vGuide.kind == SnapGuideKind.ALIGN_PEER) peerDashEffect else null
            )
        }

        val hGuide = snapState.horizontalGuide
        if (hGuide != null) {
            val y = hGuide.position * h
            val color = guideColor(hGuide.kind)
            drawLine(
                color = color.copy(alpha = 0.28f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = glowWidthPx
            )
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = lineWidthPx,
                pathEffect = if (hGuide.kind == SnapGuideKind.ALIGN_PEER) peerDashEffect else null
            )
        }

        if (vGuide != null && hGuide != null) {
            val cx = vGuide.position * w
            val cy = hGuide.position * h
            drawCircle(
                color = MeshCyan.copy(alpha = 0.35f),
                radius = 7.dp.toPx(),
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = Offset(cx, cy)
            )
        }
    }
}
