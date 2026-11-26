package com.klabcc.piersocial

import com.klabcc.piersocial.ui.screens.SnapGuideKind
import com.klabcc.piersocial.ui.screens.StorySnapEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StorySnapEngineTest {

    @Test
    fun snapSticker_snapsToCenterAxesWithinThreshold() {
        val result = StorySnapEngine.snapSticker(
            rawX = 0.512f,
            rawY = 0.489f,
            enabled = true
        )
        assertEquals(0.5f, result.snappedX, 0.0001f)
        assertEquals(0.5f, result.snappedY, 0.0001f)
        assertEquals(SnapGuideKind.CENTER, result.verticalGuide?.kind)
        assertEquals(SnapGuideKind.CENTER, result.horizontalGuide?.kind)
    }

    @Test
    fun snapSticker_snapsEdgesToSafeMargins() {
        val result = StorySnapEngine.snapSticker(
            rawX = 0.172f, // left edge at 0.172 - 0.10 = 0.072 (near SAFE_LEFT 0.08)
            rawY = 0.195f, // top edge at 0.195 - 0.05 = 0.145 (near SAFE_TOP 0.14)
            halfWidthNorm = 0.10f,
            halfHeightNorm = 0.05f,
            enabled = true
        )
        assertEquals(0.18f, result.snappedX, 0.0001f)
        assertEquals(0.19f, result.snappedY, 0.0001f)
        assertEquals(SnapGuideKind.SAFE_MARGIN, result.verticalGuide?.kind)
        assertEquals(StorySnapEngine.SAFE_LEFT, result.verticalGuide!!.position, 0.0001f)
        assertEquals(SnapGuideKind.SAFE_MARGIN, result.horizontalGuide?.kind)
        assertEquals(StorySnapEngine.SAFE_TOP, result.horizontalGuide!!.position, 0.0001f)
    }

    @Test
    fun snapSticker_alignsWithPeerStickerCenters() {
        val result = StorySnapEngine.snapSticker(
            rawX = 0.31f,
            rawY = 0.67f,
            peerXs = listOf(0.30f),
            peerYs = listOf(0.68f),
            enabled = true
        )
        assertEquals(0.30f, result.snappedX, 0.0001f)
        assertEquals(0.68f, result.snappedY, 0.0001f)
        assertEquals(SnapGuideKind.ALIGN_PEER, result.verticalGuide?.kind)
        assertEquals(SnapGuideKind.ALIGN_PEER, result.horizontalGuide?.kind)
    }

    @Test
    fun snapSticker_releasesCleanlyWhenDraggedPastThresholdOrDisabled() {
        val released = StorySnapEngine.snapSticker(
            rawX = 0.54f,
            rawY = 0.54f,
            enabled = true
        )
        assertEquals(0.54f, released.snappedX, 0.0001f)
        assertEquals(0.54f, released.snappedY, 0.0001f)
        assertNull(released.verticalGuide)
        assertNull(released.horizontalGuide)

        val disabled = StorySnapEngine.snapSticker(
            rawX = 0.501f,
            rawY = 0.499f,
            enabled = false
        )
        assertEquals(0.501f, disabled.snappedX, 0.0001f)
        assertEquals(0.499f, disabled.snappedY, 0.0001f)
        assertNull(disabled.verticalGuide)
        assertNull(disabled.horizontalGuide)
    }

    @Test
    fun snapPhotoPan_snapsToZeroCenterWithinThreshold() {
        val snapped = StorySnapEngine.snapPhotoPan(rawPanX = 0.015f, rawPanY = -0.02f, enabled = true)
        assertEquals(0f, snapped.snappedX, 0.0001f)
        assertEquals(0f, snapped.snappedY, 0.0001f)
        assertNotNull(snapped.verticalGuide)
        assertNotNull(snapped.horizontalGuide)
    }
}
