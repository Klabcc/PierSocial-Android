package com.klabcc.piersocial.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PhotoFilter(val displayName: String) {
    ORIGINAL("Original"),
    BW("B&W"),
    VINTAGE("Vintage"),
    WARM("Warm"),
    COOL("Cool"),
    HIGH_CONTRAST("Contrast"),
    NOIR("Noir"),
    CYBER("Cyber"),
    GOLDEN("Golden"),
    FADE("Fade")
}

enum class StoryFontStyle(val label: String) {
    SANS("Clean"),
    SERIF("Classic"),
    MONO("Mono"),
    BOLD_DISPLAY("Display")
}

enum class BrushStyle(val label: String) {
    PEN("Pen"),
    NEON("Neon"),
    MARKER("Marker"),
    DASHED("Dashed")
}

enum class TextBadgeStyle(val label: String) {
    PILL("Pill"),
    SOLID("Box"),
    OUTLINE("Clean")
}

enum class StoryBackdrop(val label: String, val startColor: Int, val endColor: Int) {
    NONE("Dark", 0xFF0A0D12.toInt(), 0xFF0A0D12.toInt()),
    AURORA("Aurora", 0xFF0B3B4F.toInt(), 0xFF3E1F6B.toInt()),
    SUNSET("Sunset", 0xFF8E24AA.toInt(), 0xFFFF6D00.toInt()),
    CYBER("Cyber", 0xFF004D40.toInt(), 0xFF1A237E.toInt()),
    MIDNIGHT("Midnight", 0xFF0D1B2A.toInt(), 0xFF1B263B.toInt()),
    CORAL("Coral", 0xFFAD1457.toInt(), 0xFFD84315.toInt())
}

data class PhotoAdjustments(
    val filter: PhotoFilter = PhotoFilter.ORIGINAL,
    val brightness: Float = 0f,       // -80f .. +80f
    val contrast: Float = 1f,         // 0.5f .. 1.8f
    val saturation: Float = 1f,       // 0.0f .. 2.0f
    val vignette: Float = 0f,         // 0.0f .. 1.0f
    val rotationDegrees: Float = 0f,  // 0, 90, 180, 270
    val flipHorizontal: Boolean = false,
    val zoom: Float = 1f,             // 1.0f .. 4.0f
    val normalizedPanX: Float = 0f,   // -0.5f .. 0.5f
    val normalizedPanY: Float = 0f    // -0.5f .. 0.5f
)

data class StoryBrushStroke(
    val points: List<Pair<Float, Float>>, // normalized 0..1 coordinates
    val colorArgb: Int,
    val strokeWidthFraction: Float = 0.012f,
    val style: BrushStyle = BrushStyle.PEN
)

data class StoryTextSticker(
    val id: String,
    val text: String,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.45f,
    val colorArgb: Int = Color.WHITE,
    val fontStyle: StoryFontStyle = StoryFontStyle.BOLD_DISPLAY,
    val badgeStyle: TextBadgeStyle = TextBadgeStyle.PILL,
    val scale: Float = 1f
)

data class StoryEmojiSticker(
    val id: String,
    val emoji: String,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.55f,
    val scale: Float = 1f
)

data class StoryOverlayData(
    val backdrop: StoryBackdrop = StoryBackdrop.NONE,
    val brushStrokes: List<StoryBrushStroke> = emptyList(),
    val textStickers: List<StoryTextSticker> = emptyList(),
    val emojiStickers: List<StoryEmojiSticker> = emptyList()
)

data class ProcessedImageResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String
)

object ImagePipeline {
    const val MAX_DIMENSION_PX = 1080

    fun buildColorMatrix(adjustments: PhotoAdjustments): ColorMatrix {
        val result = ColorMatrix()

        val preset = when (adjustments.filter) {
            PhotoFilter.ORIGINAL -> ColorMatrix()
            PhotoFilter.BW -> ColorMatrix().apply { setSaturation(0f) }
            PhotoFilter.VINTAGE -> ColorMatrix(
                floatArrayOf(
                    0.9f, 0.25f, 0.1f, 0f, 12f,
                    0.05f, 0.85f, 0.1f, 0f, 6f,
                    0.05f, 0.15f, 0.65f, 0f, -8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            PhotoFilter.WARM -> ColorMatrix(
                floatArrayOf(
                    1.12f, 0f, 0f, 0f, 10f,
                    0f, 1.03f, 0f, 0f, 4f,
                    0f, 0f, 0.88f, 0f, -6f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            PhotoFilter.COOL -> ColorMatrix(
                floatArrayOf(
                    0.88f, 0f, 0f, 0f, -6f,
                    0f, 1.02f, 0f, 0f, 2f,
                    0f, 0f, 1.16f, 0f, 12f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            PhotoFilter.HIGH_CONTRAST -> ColorMatrix(
                floatArrayOf(
                    1.45f, 0f, 0f, 0f, -55f,
                    0f, 1.45f, 0f, 0f, -55f,
                    0f, 0f, 1.45f, 0f, -55f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            PhotoFilter.NOIR -> {
                ColorMatrix().apply { setSaturation(0f) }.also { m ->
                    m.postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                1.35f, 0f, 0f, 0f, -40f,
                                0f, 1.35f, 0f, 0f, -40f,
                                0f, 0f, 1.35f, 0f, -40f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
            }
            PhotoFilter.CYBER -> ColorMatrix(
                floatArrayOf(
                    0.9f, 0.1f, 0.25f, 0f, -5f,
                    0.0f, 1.12f, 0.15f, 0f, 8f,
                    0.2f, 0.1f, 1.25f, 0f, 18f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            PhotoFilter.GOLDEN -> ColorMatrix(
                floatArrayOf(
                    1.18f, 0.08f, 0f, 0f, 14f,
                    0.04f, 1.08f, 0f, 0f, 8f,
                    0f, 0f, 0.78f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            PhotoFilter.FADE -> ColorMatrix(
                floatArrayOf(
                    0.85f, 0.05f, 0.05f, 0f, 22f,
                    0.05f, 0.85f, 0.05f, 0f, 22f,
                    0.05f, 0.05f, 0.85f, 0f, 26f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        result.postConcat(preset)

        if (adjustments.saturation != 1f) {
            val satMatrix = ColorMatrix().apply { setSaturation(adjustments.saturation) }
            result.postConcat(satMatrix)
        }

        val c = adjustments.contrast
        val t = (1f - c) * 128f + adjustments.brightness
        val cbMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(cbMatrix)
        return result
    }

    fun decodeUriToBitmap(context: Context, uri: Uri, maxEdge: Int = 1440): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxEdge * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: return null

        val orientation = resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        return rotateForExif(raw, orientation)
    }

    private fun rotateForExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun processAndExport(
        source: Bitmap?,
        isStory: Boolean,
        adjustments: PhotoAdjustments,
        storyOverlay: StoryOverlayData? = null,
        quality: Int = 82
    ): ProcessedImageResult {
        val (outW, outH) = if (isStory) {
            val w = (MAX_DIMENSION_PX * 9f / 16f).roundToInt()
            w to MAX_DIMENSION_PX
        } else {
            val edge = min(
                MAX_DIMENSION_PX,
                max(source?.width ?: MAX_DIMENSION_PX, source?.height ?: MAX_DIMENSION_PX)
            ).coerceAtLeast(360)
            edge to edge
        }

        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val backdrop = storyOverlay?.backdrop ?: StoryBackdrop.NONE
        if (backdrop != StoryBackdrop.NONE) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, outW.toFloat(), outH.toFloat(),
                    backdrop.startColor, backdrop.endColor,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), bgPaint)
        } else {
            canvas.drawColor(Color.BLACK)
        }

        if (source != null) {
            val transformed = applyOrientationTransform(
                source,
                adjustments.rotationDegrees,
                adjustments.flipHorizontal
            )

            val scaleToFill = max(outW.toFloat() / transformed.width, outH.toFloat() / transformed.height)
            val totalScale = scaleToFill * adjustments.zoom.coerceIn(1f, 4f)
            val drawW = transformed.width * totalScale
            val drawH = transformed.height * totalScale

            val centerX = outW / 2f + adjustments.normalizedPanX * outW
            val centerY = outH / 2f + adjustments.normalizedPanY * outH
            val left = (centerX - drawW / 2f).coerceIn(outW - drawW, 0f)
            val top = (centerY - drawH / 2f).coerceIn(outH - drawH, 0f)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(buildColorMatrix(adjustments))
            }
            canvas.drawBitmap(transformed, null, RectF(left, top, left + drawW, top + drawH), paint)
            if (transformed !== source) transformed.recycle()
        }

        if (adjustments.vignette > 0.01f) {
            val vigAlpha = (adjustments.vignette.coerceIn(0f, 1f) * 200).roundToInt()
            val vigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    outW / 2f,
                    outH / 2f,
                    max(outW, outH) * 0.72f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(vigAlpha, 0, 0, 0)),
                    floatArrayOf(0.42f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), vigPaint)
        }

        if (storyOverlay != null) {
            drawStoryOverlays(canvas, outW.toFloat(), outH.toFloat(), storyOverlay)
        }

        val compressed = compressToWebP(output, quality)
        output.recycle()

        return ProcessedImageResult(
            bytes = compressed,
            width = outW,
            height = outH,
            mimeType = "image/webp"
        )
    }

    private fun applyOrientationTransform(
        source: Bitmap,
        rotationDegrees: Float,
        flipHorizontal: Boolean
    ): Bitmap {
        val normRot = ((rotationDegrees % 360f) + 360f) % 360f
        if (normRot == 0f && !flipHorizontal) return source
        val matrix = Matrix().apply {
            if (flipHorizontal) postScale(-1f, 1f)
            if (normRot != 0f) postRotate(normRot)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun drawStoryOverlays(
        canvas: Canvas,
        width: Float,
        height: Float,
        overlay: StoryOverlayData
    ) {
        overlay.brushStrokes.forEach { stroke ->
            if (stroke.points.size >= 2) {
                val path = Path().apply {
                    val first = stroke.points.first()
                    moveTo(first.first * width, first.second * height)
                    for (i in 1 until stroke.points.size) {
                        val pt = stroke.points[i]
                        lineTo(pt.first * width, pt.second * height)
                    }
                }
                val baseWidth = stroke.strokeWidthFraction * width
                when (stroke.style) {
                    BrushStyle.PEN -> {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = stroke.colorArgb
                            style = Paint.Style.STROKE
                            strokeWidth = baseWidth
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                        }
                        canvas.drawPath(path, paint)
                    }
                    BrushStyle.MARKER -> {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = stroke.colorArgb
                            alpha = 140
                            style = Paint.Style.STROKE
                            strokeWidth = baseWidth * 2.1f
                            strokeCap = Paint.Cap.SQUARE
                            strokeJoin = Paint.Join.ROUND
                        }
                        canvas.drawPath(path, paint)
                    }
                    BrushStyle.NEON -> {
                        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = stroke.colorArgb
                            style = Paint.Style.STROKE
                            strokeWidth = baseWidth * 2.2f
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            maskFilter = BlurMaskFilter(baseWidth * 1.4f, BlurMaskFilter.Blur.NORMAL)
                        }
                        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.WHITE
                            style = Paint.Style.STROKE
                            strokeWidth = baseWidth * 0.72f
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                        }
                        canvas.drawPath(path, glowPaint)
                        canvas.drawPath(path, corePaint)
                    }
                    BrushStyle.DASHED -> {
                        val dash = baseWidth * 2.4f
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = stroke.colorArgb
                            style = Paint.Style.STROKE
                            strokeWidth = baseWidth
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            pathEffect = DashPathEffect(floatArrayOf(dash, dash * 1.3f), 0f)
                        }
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }

        overlay.textStickers.forEach { sticker ->
            val typeface = when (sticker.fontStyle) {
                StoryFontStyle.SANS -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                StoryFontStyle.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                StoryFontStyle.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                StoryFontStyle.BOLD_DISPLAY -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val textSize = width * 0.07f * sticker.scale
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = sticker.colorArgb
                this.textSize = textSize
                this.typeface = typeface
                textAlign = Paint.Align.CENTER
                if (sticker.badgeStyle == TextBadgeStyle.OUTLINE) {
                    setShadowLayer(10f, 0f, 3f, Color.argb(215, 0, 0, 0))
                }
            }
            val cx = sticker.normalizedX * width
            val cy = sticker.normalizedY * height
            val textWidth = textPaint.measureText(sticker.text)
            val padH = textSize * 0.45f
            val padV = textSize * 0.35f
            val rect = RectF(
                cx - textWidth / 2f - padH,
                cy - textSize + padV * 0.2f,
                cx + textWidth / 2f + padH,
                cy + padV
            )
            when (sticker.badgeStyle) {
                TextBadgeStyle.PILL -> {
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(135, 0, 0, 0) }
                    canvas.drawRoundRect(rect, 28f, 28f, bgPaint)
                }
                TextBadgeStyle.SOLID -> {
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 18, 22, 32) }
                    canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
                }
                TextBadgeStyle.OUTLINE -> Unit
            }
            canvas.drawText(sticker.text, cx, cy, textPaint)
        }

        overlay.emojiStickers.forEach { emoji ->
            val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = width * 0.13f * emoji.scale
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                emoji.emoji,
                emoji.normalizedX * width,
                emoji.normalizedY * height,
                emojiPaint
            )
        }
    }

    @Suppress("DEPRECATION")
    fun compressToWebP(bitmap: Bitmap, quality: Int = 82): ByteArray {
        val stream = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality.coerceIn(30, 95), stream)
        return stream.toByteArray()
    }
}
