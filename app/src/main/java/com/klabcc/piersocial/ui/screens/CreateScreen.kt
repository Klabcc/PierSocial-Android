package com.klabcc.piersocial.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.klabcc.piersocial.data.storage.BrushStyle
import com.klabcc.piersocial.data.storage.ImagePipeline
import com.klabcc.piersocial.data.storage.PhotoAdjustments
import com.klabcc.piersocial.data.storage.PhotoFilter
import com.klabcc.piersocial.data.storage.StoryAudioRecorder
import com.klabcc.piersocial.data.storage.StoryBackdrop
import com.klabcc.piersocial.data.storage.StoryBrushStroke
import com.klabcc.piersocial.data.storage.StoryEmojiSticker
import com.klabcc.piersocial.data.storage.StoryFontStyle
import com.klabcc.piersocial.data.storage.StoryOverlayData
import com.klabcc.piersocial.data.storage.StoryTextSticker
import com.klabcc.piersocial.data.storage.TextBadgeStyle
import com.klabcc.piersocial.ui.components.formatByteSize
import com.klabcc.piersocial.ui.theme.BorderSubtle
import com.klabcc.piersocial.ui.theme.MeshCyan
import com.klabcc.piersocial.ui.theme.ObsidianBg
import com.klabcc.piersocial.ui.theme.SignalAmber
import com.klabcc.piersocial.ui.theme.SignalGreen
import com.klabcc.piersocial.ui.theme.SignalRed
import com.klabcc.piersocial.ui.theme.SurfaceCard
import com.klabcc.piersocial.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private enum class EditorTool {
    CROP_AND_FILTERS,
    BACKDROP,
    DRAW,
    TEXT,
    STICKERS,
    AUDIO
}

@Composable
fun CreateScreen(
    contentPadding: PaddingValues,
    initialStoryMode: Boolean = false,
    developerMode: Boolean = false,
    onPublishPost: (Bitmap, PhotoAdjustments, String, File?, Int, String?, List<String>) -> Unit,
    onPublishStory: (Bitmap?, PhotoAdjustments, StoryOverlayData, String, File?, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isStoryMode by remember(initialStoryMode) { mutableStateOf(initialStoryMode) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val audioRecorder = remember { StoryAudioRecorder(context) }
    val audioState by audioRecorder.state.collectAsState()
    DisposableEffect(Unit) {
        onDispose { audioRecorder.release() }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val decoded = ImagePipeline.decodeUriToBitmap(context, uri)
            if (decoded != null) {
                sourceBitmap = decoded
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch { audioRecorder.importClipFromUri(uri) }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            audioRecorder.startRecording(scope)
        }
    }

    var adjustments by remember { mutableStateOf(PhotoAdjustments()) }
    var caption by remember { mutableStateOf("") }
    var activeTool by remember { mutableStateOf(EditorTool.CROP_AND_FILTERS) }

    // Optional Feed Poll state
    var includePoll by remember { mutableStateOf(false) }
    var pollQuestion by remember { mutableStateOf("") }
    val pollOptions = remember { mutableStateListOf("", "") }

    // Story Canvas Overlay states
    var selectedBackdrop by remember { mutableStateOf(StoryBackdrop.NONE) }
    val brushStrokes = remember { mutableStateListOf<StoryBrushStroke>() }
    var activeStrokePoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var selectedBrushColor by remember { mutableStateOf(Color(0xFF00E5FF)) }
    var selectedBrushStyle by remember { mutableStateOf(BrushStyle.PEN) }
    var brushWidthFraction by remember { mutableFloatStateOf(0.014f) }

    val textStickers = remember { mutableStateListOf<StoryTextSticker>() }
    val emojiStickers = remember { mutableStateListOf<StoryEmojiSticker>() }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var snappingEnabled by remember { mutableStateOf(true) }
    var snapState by remember { mutableStateOf(StorySnapState()) }
    val haptics = LocalHapticFeedback.current
    val latestSnappingEnabled by rememberUpdatedState(snappingEnabled)
    val latestIsStoryMode by rememberUpdatedState(isStoryMode)
    val latestAdjustments by rememberUpdatedState(adjustments)

    val paletteColors = listOf(
        Color.White,
        Color(0xFF00E5FF),
        Color(0xFF8C5EFF),
        Color(0xFFFF4081),
        Color(0xFF00E676),
        Color(0xFFFFB300),
        Color(0xFFFF5252),
        Color.Black
    )

    val canPublish = !audioState.isRecording && !audioState.isProcessingImport && if (isStoryMode) {
        sourceBitmap != null ||
            selectedBackdrop != StoryBackdrop.NONE ||
            brushStrokes.isNotEmpty() ||
            textStickers.isNotEmpty() ||
            emojiStickers.isNotEmpty() ||
            audioState.recordedFile != null
    } else {
        sourceBitmap != null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding()
            )
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Mode Selector + Source Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !isStoryMode,
                onClick = {
                    isStoryMode = false
                    if (activeTool != EditorTool.CROP_AND_FILTERS && activeTool != EditorTool.AUDIO) {
                        activeTool = EditorTool.CROP_AND_FILTERS
                    }
                },
                label = { Text("Feed (1:1)") }
            )
            FilterChip(
                selected = isStoryMode,
                onClick = { isStoryMode = true },
                label = { Text("Story (9:16)") }
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Gallery", fontSize = 12.sp)
            }
        }

        // Interactive Viewport + Story Canvas
        val targetAspect = if (isStoryMode) 9f / 16f else 1f
        val colorMatrixValues = remember(adjustments) {
            ImagePipeline.buildColorMatrix(adjustments).array
        }
        val backdropBrush = remember(selectedBackdrop, isStoryMode) {
            if (isStoryMode && selectedBackdrop != StoryBackdrop.NONE) {
                Brush.linearGradient(
                    listOf(Color(selectedBackdrop.startColor), Color(selectedBackdrop.endColor))
                )
            } else {
                Brush.linearGradient(listOf(Color.Black, Color.Black))
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth(if (isStoryMode) 0.72f else 1f)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(targetAspect)
                .clip(RoundedCornerShape(16.dp))
                .background(backdropBrush)
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                .clipToBounds()
        ) {
            val boxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val boxHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

            val bmp = sourceBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Preview",
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix(colorMatrixValues)),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = adjustments.zoom * (if (adjustments.flipHorizontal) -1f else 1f)
                            scaleY = adjustments.zoom
                            rotationZ = adjustments.rotationDegrees
                            translationX = adjustments.normalizedPanX * boxWidthPx
                            translationY = adjustments.normalizedPanY * boxHeightPx
                        }
                        .let { mod ->
                            if (activeTool == EditorTool.CROP_AND_FILTERS) {
                                var rawPanX by remember { mutableFloatStateOf(adjustments.normalizedPanX) }
                                var rawPanY by remember { mutableFloatStateOf(adjustments.normalizedPanY) }
                                mod
                                    .pointerInput(boxWidthPx, boxHeightPx) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Final)
                                                if (event.changes.all { !it.pressed }) {
                                                    rawPanX = latestAdjustments.normalizedPanX
                                                    rawPanY = latestAdjustments.normalizedPanY
                                                    if (snapState.isDragging && snapState.draggingStickerId == null) {
                                                        snapState = StorySnapState()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .pointerInput(boxWidthPx, boxHeightPx) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val cur = latestAdjustments
                                            val nextZoom = (cur.zoom * zoom).coerceIn(1f, 4f)
                                            rawPanX = (rawPanX + pan.x / boxWidthPx).coerceIn(-0.45f, 0.45f)
                                            rawPanY = (rawPanY + pan.y / boxHeightPx).coerceIn(-0.45f, 0.45f)
                                            if (latestIsStoryMode && latestSnappingEnabled && (pan.x != 0f || pan.y != 0f)) {
                                                val snap = StorySnapEngine.snapPhotoPan(rawPanX, rawPanY, enabled = true)
                                                val prevV = snapState.verticalGuide
                                                val prevH = snapState.horizontalGuide
                                                if ((snap.verticalGuide != null && snap.verticalGuide != prevV) ||
                                                    (snap.horizontalGuide != null && snap.horizontalGuide != prevH)
                                                ) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                snapState = StorySnapState(
                                                    isDragging = true,
                                                    draggingStickerId = null,
                                                    verticalGuide = snap.verticalGuide,
                                                    horizontalGuide = snap.horizontalGuide
                                                )
                                                adjustments = cur.copy(
                                                    zoom = nextZoom,
                                                    normalizedPanX = snap.snappedX,
                                                    normalizedPanY = snap.snappedY
                                                )
                                            } else {
                                                adjustments = cur.copy(
                                                    zoom = nextZoom,
                                                    normalizedPanX = rawPanX,
                                                    normalizedPanY = rawPanY
                                                )
                                            }
                                        }
                                    }
                            } else {
                                mod
                            }
                        }
                )
            } else if (!isStoryMode || selectedBackdrop == StoryBackdrop.NONE) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Select Photo",
                        tint = MeshCyan,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (isStoryMode) "Pick a photo or choose a Backdrop" else "Tap to pick a photo from Gallery",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (developerMode) {
                            if (isStoryMode) "9:16 Story • <= 1080px WebP" else "1:1 Feed • <= 1080px WebP"
                        } else {
                            if (isStoryMode) "9:16 Story" else "1:1 Feed"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }

            // Vignette preview overlay
            if (adjustments.vignette > 0.01f && bmp != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = (adjustments.vignette * 0.78f).coerceIn(0f, 0.85f))
                                )
                            )
                        )
                )
            }

            // Story Canvas Drawing Layer
            if (isStoryMode) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .let { mod ->
                            if (activeTool == EditorTool.DRAW) {
                                mod.pointerInput(selectedBrushColor, brushWidthFraction, selectedBrushStyle) {
                                    detectDragGestures(
                                        onDragStart = { start ->
                                            activeStrokePoints = listOf(
                                                (start.x / size.width).coerceIn(0f, 1f) to
                                                    (start.y / size.height).coerceIn(0f, 1f)
                                            )
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val pt = (change.position.x / size.width).coerceIn(0f, 1f) to
                                                (change.position.y / size.height).coerceIn(0f, 1f)
                                            activeStrokePoints = activeStrokePoints + pt
                                        },
                                        onDragEnd = {
                                            if (activeStrokePoints.size >= 2) {
                                                brushStrokes.add(
                                                    StoryBrushStroke(
                                                        points = activeStrokePoints,
                                                        colorArgb = selectedBrushColor.toArgb(),
                                                        strokeWidthFraction = brushWidthFraction,
                                                        style = selectedBrushStyle
                                                    )
                                                )
                                            }
                                            activeStrokePoints = emptyList()
                                        }
                                    )
                                }
                            } else mod
                        }
                ) {
                    val drawStroke: (List<Pair<Float, Float>>, Color, Float, BrushStyle) -> Unit =
                        { pts, color, frac, style ->
                            if (pts.size >= 2) {
                                val path = Path().apply {
                                    moveTo(pts.first().first * size.width, pts.first().second * size.height)
                                    for (i in 1 until pts.size) {
                                        lineTo(pts[i].first * size.width, pts[i].second * size.height)
                                    }
                                }
                                val baseWidth = frac * size.width
                                when (style) {
                                    BrushStyle.PEN -> drawPath(
                                        path = path,
                                        color = color,
                                        style = Stroke(width = baseWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                    BrushStyle.MARKER -> drawPath(
                                        path = path,
                                        color = color.copy(alpha = 0.55f),
                                        style = Stroke(width = baseWidth * 2.1f, cap = StrokeCap.Square, join = StrokeJoin.Round)
                                    )
                                    BrushStyle.NEON -> {
                                        drawPath(
                                            path = path,
                                            color = color.copy(alpha = 0.55f),
                                            style = Stroke(width = baseWidth * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                        )
                                        drawPath(
                                            path = path,
                                            color = Color.White,
                                            style = Stroke(width = baseWidth * 0.72f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                        )
                                    }
                                    BrushStyle.DASHED -> {
                                        val dash = baseWidth * 2.4f
                                        drawPath(
                                            path = path,
                                            color = color,
                                            style = Stroke(
                                                width = baseWidth,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round,
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 1.3f), 0f)
                                            )
                                        )
                                    }
                                }
                            }
                        }

                    brushStrokes.forEach { stroke ->
                        drawStroke(stroke.points, Color(stroke.colorArgb), stroke.strokeWidthFraction, stroke.style)
                    }
                    if (activeStrokePoints.size >= 2) {
                        drawStroke(activeStrokePoints, selectedBrushColor, brushWidthFraction, selectedBrushStyle)
                    }
                }

                // Live Snapping Guides Overlay (visible only while dragging stickers or panning)
                if (snappingEnabled) {
                    StorySnapGuidesOverlay(snapState = snapState)
                }

                // Draggable Text Stickers with Snapping
                textStickers.forEach { sticker ->
                    var stickerSize by remember(sticker.id) { mutableStateOf(IntSize.Zero) }
                    val latestSize by rememberUpdatedState(stickerSize)
                    val latestBoxWidth by rememberUpdatedState(boxWidthPx)
                    val latestBoxHeight by rememberUpdatedState(boxHeightPx)
                    val isDraggingThis = snapState.isDragging && snapState.draggingStickerId == sticker.id

                    val fontFamily = when (sticker.fontStyle) {
                        StoryFontStyle.SANS, StoryFontStyle.BOLD_DISPLAY -> FontFamily.SansSerif
                        StoryFontStyle.SERIF -> FontFamily.Serif
                        StoryFontStyle.MONO -> FontFamily.Monospace
                    }
                    val badgeBg = when (sticker.badgeStyle) {
                        TextBadgeStyle.PILL -> Color.Black.copy(alpha = 0.52f)
                        TextBadgeStyle.SOLID -> Color(0xE0121620)
                        TextBadgeStyle.OUTLINE -> Color.Transparent
                    }
                    val badgeShape = when (sticker.badgeStyle) {
                        TextBadgeStyle.PILL -> RoundedCornerShape(50)
                        TextBadgeStyle.SOLID -> RoundedCornerShape(6.dp)
                        TextBadgeStyle.OUTLINE -> RoundedCornerShape(0.dp)
                    }
                    Box(
                        modifier = Modifier
                            .onSizeChanged { stickerSize = it }
                            .offset {
                                IntOffset(
                                    ((sticker.normalizedX * boxWidthPx) - stickerSize.width / 2f).roundToInt(),
                                    ((sticker.normalizedY * boxHeightPx) - stickerSize.height / 2f).roundToInt()
                                )
                            }
                            .pointerInput(sticker.id) {
                                var rawX = sticker.normalizedX
                                var rawY = sticker.normalizedY
                                detectDragGestures(
                                    onDragStart = {
                                        val cur = textStickers.firstOrNull { it.id == sticker.id }
                                        rawX = cur?.normalizedX ?: sticker.normalizedX
                                        rawY = cur?.normalizedY ?: sticker.normalizedY
                                        snapState = StorySnapState(
                                            isDragging = true,
                                            draggingStickerId = sticker.id
                                        )
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val idx = textStickers.indexOfFirst { it.id == sticker.id }
                                        if (idx < 0) return@detectDragGestures
                                        val w = latestBoxWidth.coerceAtLeast(1f)
                                        val h = latestBoxHeight.coerceAtLeast(1f)
                                        rawX = (rawX + dragAmount.x / w).coerceIn(0.06f, 0.94f)
                                        rawY = (rawY + dragAmount.y / h).coerceIn(0.06f, 0.94f)
                                        val halfW = (latestSize.width / 2f) / w
                                        val halfH = (latestSize.height / 2f) / h
                                        val peerXs = textStickers.filter { it.id != sticker.id }.map { it.normalizedX } +
                                            emojiStickers.map { it.normalizedX }
                                        val peerYs = textStickers.filter { it.id != sticker.id }.map { it.normalizedY } +
                                            emojiStickers.map { it.normalizedY }
                                        val snap = StorySnapEngine.snapSticker(
                                            rawX = rawX,
                                            rawY = rawY,
                                            halfWidthNorm = halfW,
                                            halfHeightNorm = halfH,
                                            peerXs = peerXs,
                                            peerYs = peerYs,
                                            enabled = latestSnappingEnabled
                                        )
                                        if ((snap.verticalGuide != null && snap.verticalGuide != snapState.verticalGuide) ||
                                            (snap.horizontalGuide != null && snap.horizontalGuide != snapState.horizontalGuide)
                                        ) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        snapState = StorySnapState(
                                            isDragging = true,
                                            draggingStickerId = sticker.id,
                                            verticalGuide = snap.verticalGuide,
                                            horizontalGuide = snap.horizontalGuide
                                        )
                                        textStickers[idx] = textStickers[idx].copy(
                                            normalizedX = snap.snappedX,
                                            normalizedY = snap.snappedY
                                        )
                                    },
                                    onDragEnd = { snapState = StorySnapState() },
                                    onDragCancel = { snapState = StorySnapState() }
                                )
                            }
                            .background(badgeBg, badgeShape)
                            .then(
                                if (isDraggingThis) {
                                    Modifier.border(1.dp, MeshCyan.copy(alpha = 0.85f), badgeShape)
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = sticker.text,
                            color = Color(sticker.colorArgb),
                            fontFamily = fontFamily,
                            fontWeight = if (sticker.fontStyle == StoryFontStyle.SANS) FontWeight.Medium else FontWeight.Bold,
                            fontSize = (20 * sticker.scale).sp
                        )
                    }
                }

                // Draggable Emoji Stickers with Snapping
                emojiStickers.forEach { emoji ->
                    var emojiSize by remember(emoji.id) { mutableStateOf(IntSize.Zero) }
                    val latestSize by rememberUpdatedState(emojiSize)
                    val latestBoxWidth by rememberUpdatedState(boxWidthPx)
                    val latestBoxHeight by rememberUpdatedState(boxHeightPx)
                    val isDraggingThis = snapState.isDragging && snapState.draggingStickerId == emoji.id

                    Box(
                        modifier = Modifier
                            .onSizeChanged { emojiSize = it }
                            .offset {
                                IntOffset(
                                    ((emoji.normalizedX * boxWidthPx) - emojiSize.width / 2f).roundToInt(),
                                    ((emoji.normalizedY * boxHeightPx) - emojiSize.height / 2f).roundToInt()
                                )
                            }
                            .pointerInput(emoji.id) {
                                var rawX = emoji.normalizedX
                                var rawY = emoji.normalizedY
                                detectDragGestures(
                                    onDragStart = {
                                        val cur = emojiStickers.firstOrNull { it.id == emoji.id }
                                        rawX = cur?.normalizedX ?: emoji.normalizedX
                                        rawY = cur?.normalizedY ?: emoji.normalizedY
                                        snapState = StorySnapState(
                                            isDragging = true,
                                            draggingStickerId = emoji.id
                                        )
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val idx = emojiStickers.indexOfFirst { it.id == emoji.id }
                                        if (idx < 0) return@detectDragGestures
                                        val w = latestBoxWidth.coerceAtLeast(1f)
                                        val h = latestBoxHeight.coerceAtLeast(1f)
                                        rawX = (rawX + dragAmount.x / w).coerceIn(0.06f, 0.94f)
                                        rawY = (rawY + dragAmount.y / h).coerceIn(0.06f, 0.94f)
                                        val halfW = (latestSize.width / 2f) / w
                                        val halfH = (latestSize.height / 2f) / h
                                        val peerXs = textStickers.map { it.normalizedX } +
                                            emojiStickers.filter { it.id != emoji.id }.map { it.normalizedX }
                                        val peerYs = textStickers.map { it.normalizedY } +
                                            emojiStickers.filter { it.id != emoji.id }.map { it.normalizedY }
                                        val snap = StorySnapEngine.snapSticker(
                                            rawX = rawX,
                                            rawY = rawY,
                                            halfWidthNorm = halfW,
                                            halfHeightNorm = halfH,
                                            peerXs = peerXs,
                                            peerYs = peerYs,
                                            enabled = latestSnappingEnabled
                                        )
                                        if ((snap.verticalGuide != null && snap.verticalGuide != snapState.verticalGuide) ||
                                            (snap.horizontalGuide != null && snap.horizontalGuide != snapState.horizontalGuide)
                                        ) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        snapState = StorySnapState(
                                            isDragging = true,
                                            draggingStickerId = emoji.id,
                                            verticalGuide = snap.verticalGuide,
                                            horizontalGuide = snap.horizontalGuide
                                        )
                                        emojiStickers[idx] = emojiStickers[idx].copy(
                                            normalizedX = snap.snappedX,
                                            normalizedY = snap.snappedY
                                        )
                                    },
                                    onDragEnd = { snapState = StorySnapState() },
                                    onDragCancel = { snapState = StorySnapState() }
                                )
                            }
                            .then(
                                if (isDraggingThis) {
                                    Modifier
                                        .background(MeshCyan.copy(alpha = 0.14f), CircleShape)
                                        .border(1.dp, MeshCyan.copy(alpha = 0.85f), CircleShape)
                                } else {
                                    Modifier
                                }
                            )
                            .padding(4.dp)
                    ) {
                        Text(
                            text = emoji.emoji,
                            fontSize = (36 * emoji.scale).sp
                        )
                    }
                }

                // Snapping Guides toggle pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.62f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .border(
                            1.dp,
                            if (snappingEnabled) MeshCyan.copy(alpha = 0.65f) else BorderSubtle,
                            RoundedCornerShape(50)
                        )
                        .clickable {
                            snappingEnabled = !snappingEnabled
                            if (!snappingEnabled) snapState = StorySnapState()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = if (snappingEnabled) Icons.Default.GridOn else Icons.Default.GridOff,
                            contentDescription = "Toggle Snapping Guides",
                            tint = if (snappingEnabled) MeshCyan else TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (snappingEnabled) "Snap" else "Snap Off",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (snappingEnabled) Color.White else TextSecondary
                        )
                    }
                }
            } // end if (isStoryMode)

            // Attached 5s audio pill indicator on preview (Feed & Story)
            if (audioState.recordedFile != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clickable { activeTool = EditorTool.AUDIO }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MeshCyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = String.format(Locale.US, "%.1fs clip", audioState.recordedDurationMs / 1000f),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Tools Selector (Feed: Adjust + Audio; Story: full creation toolkit)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ToolButton(
                    icon = Icons.Default.Tune,
                    label = "Adjust",
                    selected = activeTool == EditorTool.CROP_AND_FILTERS,
                    onClick = { activeTool = EditorTool.CROP_AND_FILTERS }
                )
            }
            if (isStoryMode) {
                item {
                    ToolButton(
                        icon = Icons.Default.Gradient,
                        label = "Backdrop",
                        selected = activeTool == EditorTool.BACKDROP,
                        onClick = { activeTool = EditorTool.BACKDROP }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.Brush,
                        label = "Draw",
                        selected = activeTool == EditorTool.DRAW,
                        onClick = { activeTool = EditorTool.DRAW }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.TextFields,
                        label = "Text",
                        selected = activeTool == EditorTool.TEXT,
                        onClick = {
                            activeTool = EditorTool.TEXT
                            showAddTextDialog = true
                        }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.EmojiEmotions,
                        label = "Stickers",
                        selected = activeTool == EditorTool.STICKERS,
                        onClick = { activeTool = EditorTool.STICKERS }
                    )
                }
            }
            item {
                ToolButton(
                    icon = Icons.Default.Mic,
                    label = if (audioState.recordedFile != null) "Audio ✓" else "Audio 5s",
                    selected = activeTool == EditorTool.AUDIO,
                    onClick = { activeTool = EditorTool.AUDIO }
                )
            }
        }

        // Active Tool Controls
        when (activeTool) {
            EditorTool.CROP_AND_FILTERS -> {
                Text(
                    text = if (developerMode) {
                        "Color Matrix Presets (pinch/drag photo above to crop & zoom)"
                    } else {
                        "Filters & Crop (pinch or drag photo)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PhotoFilter.entries) { filter ->
                        FilterChip(
                            selected = adjustments.filter == filter,
                            onClick = { adjustments = adjustments.copy(filter = filter) },
                            label = { Text(filter.displayName) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            adjustments = adjustments.copy(
                                rotationDegrees = (adjustments.rotationDegrees + 90f) % 360f
                            )
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Rotate", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            adjustments = adjustments.copy(flipHorizontal = !adjustments.flipHorizontal)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Flip, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Flip", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { adjustments = PhotoAdjustments() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset", fontSize = 12.sp)
                    }
                }
                AdjustmentSlider(
                    label = "Brightness",
                    value = adjustments.brightness,
                    range = -80f..80f,
                    onValueChange = { adjustments = adjustments.copy(brightness = it) }
                )
                AdjustmentSlider(
                    label = "Contrast",
                    value = adjustments.contrast,
                    range = 0.5f..1.8f,
                    onValueChange = { adjustments = adjustments.copy(contrast = it) }
                )
                AdjustmentSlider(
                    label = "Saturation",
                    value = adjustments.saturation,
                    range = 0f..2f,
                    onValueChange = { adjustments = adjustments.copy(saturation = it) }
                )
                AdjustmentSlider(
                    label = "Vignette",
                    value = adjustments.vignette,
                    range = 0f..1f,
                    onValueChange = { adjustments = adjustments.copy(vignette = it) }
                )
            }

            EditorTool.BACKDROP -> {
                Text(
                    text = "Story Canvas Backdrop (works with or without a photo)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(StoryBackdrop.entries) { bg ->
                        val selected = selectedBackdrop == bg
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { selectedBackdrop = bg }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(bg.startColor), Color(bg.endColor))
                                        )
                                    )
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) MeshCyan else BorderSubtle,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = bg.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MeshCyan else TextSecondary
                            )
                        }
                    }
                }
            }

            EditorTool.DRAW -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BrushStyle.entries) { style ->
                        FilterChip(
                            selected = selectedBrushStyle == style,
                            onClick = { selectedBrushStyle = style },
                            label = { Text(style.label) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(paletteColors) { c ->
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (selectedBrushColor == c) 3.dp else 1.dp,
                                        color = if (selectedBrushColor == c) MeshCyan else BorderSubtle,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedBrushColor = c }
                            )
                        }
                    }
                    Row {
                        IconButton(onClick = { if (brushStrokes.isNotEmpty()) brushStrokes.removeAt(brushStrokes.lastIndex) }) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo stroke")
                        }
                        IconButton(onClick = { brushStrokes.clear() }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear strokes")
                        }
                    }
                }
                AdjustmentSlider(
                    label = "Brush Size",
                    value = brushWidthFraction,
                    range = 0.006f..0.035f,
                    onValueChange = { brushWidthFraction = it }
                )
            }

            EditorTool.TEXT -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAddTextDialog = true }) {
                        Text("+ Add Text Sticker")
                    }
                    if (textStickers.isNotEmpty()) {
                        OutlinedButton(onClick = { textStickers.removeAt(textStickers.lastIndex) }) {
                            Text("Remove Last")
                        }
                    }
                }
            }

            EditorTool.STICKERS -> {
                val stamps = listOf("✨", "🔥", "💜", "🌊", "⚡", "🎧", "🎶", "📸", "🌙", "☀️", "🫶", "🦋")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(stamps) { emoji ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SurfaceCard,
                                modifier = Modifier
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                                    .clickable {
                                        emojiStickers.add(
                                            StoryEmojiSticker(
                                                id = UUID.randomUUID().toString(),
                                                emoji = emoji,
                                                normalizedX = 0.35f + (emojiStickers.size % 3) * 0.15f,
                                                normalizedY = 0.35f + (emojiStickers.size % 4) * 0.1f
                                            )
                                        )
                                    }
                            ) {
                                Text(emoji, fontSize = 26.sp, modifier = Modifier.padding(10.dp))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val quickBadges = listOf("VIBES", "NOW", "NIGHT", "LIVE")
                        quickBadges.forEach { badge ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    textStickers.add(
                                        StoryTextSticker(
                                            id = UUID.randomUUID().toString(),
                                            text = badge,
                                            normalizedX = 0.5f,
                                            normalizedY = 0.25f + (textStickers.size % 3) * 0.12f,
                                            colorArgb = Color(0xFF00E5FF).toArgb(),
                                            fontStyle = StoryFontStyle.MONO,
                                            badgeStyle = TextBadgeStyle.SOLID,
                                            scale = 0.9f
                                        )
                                    )
                                },
                                label = { Text(badge) }
                            )
                        }
                    }
                    if (emojiStickers.isNotEmpty()) {
                        OutlinedButton(onClick = { emojiStickers.removeAt(emojiStickers.lastIndex) }) {
                            Text("Remove Last Sticker")
                        }
                    }
                }
            }

            EditorTool.AUDIO -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isStoryMode) "5-Second Story Audio Clip" else "5-Second Post Audio Clip",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%.1fs / 5.0s",
                                    (if (audioState.isRecording) audioState.elapsedMs else audioState.recordedDurationMs) / 1000f
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (audioState.isRecording) SignalRed else MeshCyan
                            )
                        }

                        LinearProgressIndicator(
                            progress = {
                                if (audioState.isPlayingPreview) {
                                    audioState.previewProgress
                                } else {
                                    val ms = if (audioState.isRecording) audioState.elapsedMs else audioState.recordedDurationMs
                                    (ms / StoryAudioRecorder.MAX_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (audioState.isRecording) SignalRed else MeshCyan,
                            trackColor = ObsidianBg
                        )

                        if (audioState.amplitudes.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val totalBars = audioState.amplitudes.size
                                audioState.amplitudes.forEachIndexed { idx, level ->
                                    val isPlayed = audioState.isPlayingPreview &&
                                        (idx.toFloat() / totalBars.coerceAtLeast(1)) <= audioState.previewProgress
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height((6 + level * 22).dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                when {
                                                    audioState.isRecording -> SignalRed
                                                    isPlayed -> SignalGreen
                                                    else -> MeshCyan
                                                }
                                            )
                                    )
                                }
                            }
                        }

                        if (audioState.isProcessingImport) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LinearProgressIndicator(
                                    modifier = Modifier.weight(1f),
                                    color = MeshCyan,
                                    trackColor = ObsidianBg
                                )
                                Text(
                                    text = "Compressing to mono MP3...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MeshCyan
                                )
                            }
                        }

                        if (audioState.hasImportedTrack) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ObsidianBg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = buildString {
                                                append(audioState.importedTrackName ?: "Imported Track")
                                                val codec = audioState.importedCodecLabel
                                                if (!codec.isNullOrBlank()) append(" • $codec → Mono MP3")
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Total ${formatAudioTimestamp(audioState.importedTotalDurationMs)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }

                                    // Proportional visual indicator of selected 5s portion on the full track
                                    val totalMs = audioState.importedTotalDurationMs.coerceAtLeast(1)
                                    val startFrac = (audioState.trimStartMs.toFloat() / totalMs).coerceIn(0f, 1f)
                                    val winFrac = (audioState.trimWindowMs.toFloat() / totalMs).coerceIn(0.04f, 1f)
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(14.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(SurfaceCard)
                                    ) {
                                        val barW = maxWidth
                                        Box(
                                            modifier = Modifier
                                                .offset(x = barW * startFrac)
                                                .width((barW * winFrac).coerceAtLeast(8.dp))
                                                .height(14.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MeshCyan.copy(alpha = 0.78f))
                                        )
                                    }

                                    Text(
                                        text = "Selected clip: ${formatAudioTimestamp(audioState.trimStartMs)} – ${formatAudioTimestamp(audioState.trimEndMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MeshCyan
                                    )

                                    if (audioState.maxTrimStartMs > 0) {
                                        Slider(
                                            value = audioState.trimStartMs.toFloat(),
                                            onValueChange = { raw ->
                                                audioRecorder.updateTrimStartPreview(raw.roundToInt())
                                            },
                                            onValueChangeFinished = {
                                                scope.launch { audioRecorder.commitTrimSelection() }
                                            },
                                            valueRange = 0f..audioState.maxTrimStartMs.toFloat(),
                                            enabled = !audioState.isProcessingImport,
                                            colors = SliderDefaults.colors(
                                                thumbColor = MeshCyan,
                                                activeTrackColor = MeshCyan
                                            )
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            val nudges = if (audioState.importedTotalDurationMs >= 15_000) {
                                                listOf(-5000 to "-5s", -1000 to "-1s", 1000 to "+1s", 5000 to "+5s")
                                            } else {
                                                listOf(-1000 to "-1s", -500 to "-0.5s", 500 to "+0.5s", 1000 to "+1s")
                                            }
                                            nudges.forEach { (deltaMs, label) ->
                                                OutlinedButton(
                                                    onClick = {
                                                        val nextStart = (audioState.trimStartMs + deltaMs)
                                                            .coerceIn(0, audioState.maxTrimStartMs)
                                                        scope.launch {
                                                            audioRecorder.commitTrimSelection(nextStart)
                                                        }
                                                    },
                                                    enabled = !audioState.isProcessingImport,
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(label, fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = "Track is 5.0s or shorter — full clip selected.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (audioState.isRecording) {
                                        audioRecorder.stopRecording()
                                    } else {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            audioRecorder.startRecording(scope)
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                },
                                enabled = !audioState.isProcessingImport,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (audioState.isRecording) SignalRed else MeshCyan,
                                    contentColor = if (audioState.isRecording) Color.White else Color.Black
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (audioState.isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (audioState.isRecording) "Stop" else if (audioState.recordedFile != null) "Re-record" else "Record 5s",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    audioPicker.launch(
                                        arrayOf(
                                            "audio/*",
                                            "application/ogg",
                                            "application/flac",
                                            "application/x-flac"
                                        )
                                    )
                                },
                                enabled = !audioState.isRecording && !audioState.isProcessingImport
                            ) {
                                Icon(Icons.Default.AudioFile, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Import")
                            }
                        }

                        val clipFile = audioState.recordedFile
                        if (clipFile != null && clipFile.exists()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedButton(
                                    onClick = { audioRecorder.togglePreviewPlayback(scope) },
                                    enabled = !audioState.isProcessingImport
                                ) {
                                    Icon(
                                        imageVector = if (audioState.isPlayingPreview) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (audioState.isPlayingPreview) "Stop" else "Preview")
                                }
                                Text(
                                    text = if (developerMode) {
                                        "${formatByteSize(clipFile.length())} • AAC"
                                    } else {
                                        "Ready"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SignalGreen
                                )
                                IconButton(onClick = { audioRecorder.clearClip() }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Remove Audio", tint = SignalAmber)
                                }
                            }
                        }

                        if (audioState.errorMessage != null) {
                            Text(
                                text = audioState.errorMessage!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalAmber
                            )
                        }
                    }
                }
            }
        }

        if (!isStoryMode) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (includePoll) MeshCyan.copy(alpha = 0.65f) else BorderSubtle,
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includePoll = !includePoll },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Poll,
                                contentDescription = null,
                                tint = if (includePoll) MeshCyan else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (includePoll) "Poll Attached" else "Add Poll to Post (optional)",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (includePoll) MeshCyan else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = if (includePoll) "Remove" else "Add",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (includePoll) SignalAmber else MeshCyan
                        )
                    }

                    if (includePoll) {
                        OutlinedTextField(
                            value = pollQuestion,
                            onValueChange = { if (it.length <= 100) pollQuestion = it },
                            label = { Text("Ask a question...") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        pollOptions.forEachIndexed { idx, opt ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = opt,
                                    onValueChange = { next ->
                                        if (next.length <= 45) pollOptions[idx] = next
                                    },
                                    label = { Text("Option ${idx + 1}") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                if (pollOptions.size > 2) {
                                    IconButton(onClick = { pollOptions.removeAt(idx) }) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Remove option",
                                            tint = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                        if (pollOptions.size < 4) {
                            OutlinedButton(
                                onClick = { pollOptions.add("") },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("+ Add Option", fontSize = 12.sp, color = MeshCyan)
                            }
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = caption,
            onValueChange = { caption = it },
            label = { Text(if (isStoryMode) "Story caption (optional)" else "Post caption (try #tags)") },
            maxLines = 3,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (isStoryMode) {
                    val overlay = StoryOverlayData(
                        backdrop = selectedBackdrop,
                        brushStrokes = brushStrokes.toList(),
                        textStickers = textStickers.toList(),
                        emojiStickers = emojiStickers.toList()
                    )
                    audioRecorder.stopPlayback()
                    onPublishStory(
                        sourceBitmap,
                        adjustments,
                        overlay,
                        caption,
                        audioState.recordedFile,
                        audioState.recordedDurationMs
                    )
                } else {
                    val bmp = sourceBitmap ?: return@Button
                    audioRecorder.stopPlayback()
                    val validOpts = pollOptions.map { it.trim() }.filter { it.isNotEmpty() }
                    val q = if (includePoll && pollQuestion.isNotBlank() && validOpts.size >= 2) {
                        pollQuestion.trim()
                    } else {
                        null
                    }
                    onPublishPost(
                        bmp,
                        adjustments,
                        caption,
                        audioState.recordedFile,
                        audioState.recordedDurationMs,
                        q,
                        if (q != null) validOpts else emptyList()
                    )
                }
            },
            enabled = canPublish,
            colors = ButtonDefaults.buttonColors(containerColor = MeshCyan, contentColor = Color.Black),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.Publish, null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    developerMode && isStoryMode -> "Flatten & Broadcast 24h Story (<=1080px)"
                    developerMode -> "Compress & Broadcast Post (<=1080px WebP)"
                    isStoryMode -> "Share Story"
                    else -> "Share Post"
                },
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (showAddTextDialog) {
        var newText by remember { mutableStateOf("") }
        var selectedFont by remember { mutableStateOf(StoryFontStyle.BOLD_DISPLAY) }
        var selectedBadge by remember { mutableStateOf(TextBadgeStyle.PILL) }
        var selectedTextColor by remember { mutableStateOf(Color.White) }
        var textScale by remember { mutableFloatStateOf(1f) }

        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            title = { Text("Add Story Text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newText,
                        onValueChange = { newText = it },
                        label = { Text("Overlay text") },
                        singleLine = true
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(StoryFontStyle.entries) { style ->
                            FilterChip(
                                selected = selectedFont == style,
                                onClick = { selectedFont = style },
                                label = { Text(style.label) }
                            )
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(TextBadgeStyle.entries) { style ->
                            FilterChip(
                                selected = selectedBadge == style,
                                onClick = { selectedBadge = style },
                                label = { Text(style.label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        paletteColors.take(7).forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        if (selectedTextColor == c) 2.5.dp else 1.dp,
                                        if (selectedTextColor == c) MeshCyan else BorderSubtle,
                                        CircleShape
                                    )
                                    .clickable { selectedTextColor = c }
                            )
                        }
                    }
                    AdjustmentSlider(
                        label = "Text Size",
                        value = textScale,
                        range = 0.7f..1.8f,
                        onValueChange = { textScale = it }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newText.isNotBlank()) {
                            textStickers.add(
                                StoryTextSticker(
                                    id = UUID.randomUUID().toString(),
                                    text = newText.trim(),
                                    colorArgb = if (selectedTextColor == Color.Black) AndroidColor.BLACK else selectedTextColor.toArgb(),
                                    fontStyle = selectedFont,
                                    badgeStyle = selectedBadge,
                                    scale = textScale
                                )
                            )
                        }
                        showAddTextDialog = false
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTextDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MeshCyan.copy(alpha = 0.16f) else SurfaceCard,
        modifier = Modifier
            .width(74.dp)
            .border(1.dp, if (selected) MeshCyan else BorderSubtle, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
        ) {
            Icon(icon, null, tint = if (selected) MeshCyan else TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) MeshCyan else TextSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(String.format(Locale.US, "%.2f", value), style = MaterialTheme.typography.labelMedium, color = MeshCyan)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = MeshCyan, activeTrackColor = MeshCyan)
        )
    }
}

private fun formatAudioTimestamp(ms: Int): String {
    val safeMs = ms.coerceAtLeast(0)
    val totalTenths = safeMs / 100
    val tenths = totalTenths % 10
    val totalSecs = totalTenths / 10
    val secs = totalSecs % 60
    val mins = totalSecs / 60
    return String.format(Locale.US, "%d:%02d.%d", mins, secs, tenths)
}
