package com.example.engine.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.models.BufferbloatGrade
import com.example.data.models.ConnectionType
import com.example.data.models.TestResult
import com.example.data.settings.SpeedUnit
import com.example.ui.components.formatJitterReadout
import com.example.ui.components.formatLatencyReadout
import com.example.ui.components.formatSpeedValue
import com.example.ui.components.formatTimestampMedium
import com.example.ui.theme.resolveShareCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a privacy-safe 1080x1350 Pace result card PNG into app cache and shares it via FileProvider.
 * Never includes Wi-Fi SSID, carrier name, or IP addresses.
 */
object ResultCardRenderer {

    const val CARD_WIDTH = 1080
    const val CARD_HEIGHT = 1350
    private const val CACHE_SUBDIR = "shared_cards"

    fun renderBitmap(
        result: TestResult,
        speedUnit: SpeedUnit,
        downloadColor: Int = resolveShareCardColors().first,
        uploadColor: Int = resolveShareCardColors().second,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                CARD_WIDTH.toFloat(),
                CARD_HEIGHT.toFloat(),
                intArrayOf(
                    Color.parseColor("#0B1020"),
                    Color.parseColor("#11192E"),
                    Color.parseColor("#0D1322"),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(), bgPaint)

        // Subtle ambient glow in upper-left behind brand header
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                720f,
                520f,
                withAlpha(downloadColor, 0x2A),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), 540f, glowPaint)

        val margin = 72f
        val contentWidth = CARD_WIDTH - margin * 2f

        // Header: Mini Pace gauge mark + "Pace" + Connection type pill (Wi-Fi / Mobile / Ethernet)
        drawBrandMark(
            canvas = canvas,
            cx = margin + 40f,
            cy = 138f,
            radius = 32f,
            accentColor = downloadColor,
        )

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 58f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("Pace", margin + 96f, 146f, brandPaint)

        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9BA7C0")
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText(formatTimestampMedium(result.timestamp), margin + 98f, 188f, datePaint)

        // Privacy-safe connection badge (generic connection type + non-PII band/generation, never SSID/carrier/IP)
        val connectionLabel = connectionTypeShortLabel(result)
        drawPillBadge(
            canvas = canvas,
            label = connectionLabel,
            rightEdge = CARD_WIDTH - margin,
            centerY = 142f,
            bgColor = Color.parseColor("#22304F"),
            textColor = Color.parseColor("#C9DCFF"),
        )

        val unitLabel = when (speedUnit) {
            SpeedUnit.MBPS -> "Mbps"
            SpeedUnit.MB_PER_SEC -> "MB/s"
        }

        // Hero Download card using DownloadColor
        val downloadCardTop = 264f
        val heroCardHeight = 290f
        drawThroughputHeroCard(
            canvas = canvas,
            rect = RectF(margin, downloadCardTop, margin + contentWidth, downloadCardTop + heroCardHeight),
            title = "DOWNLOAD",
            value = result.download?.let { formatSpeedValue(it, speedUnit) } ?: "-",
            unit = if (result.download != null) unitLabel else "",
            accentColor = downloadColor,
        )

        // Hero Upload card using UploadColor
        val uploadCardTop = downloadCardTop + heroCardHeight + 32f
        drawThroughputHeroCard(
            canvas = canvas,
            rect = RectF(margin, uploadCardTop, margin + contentWidth, uploadCardTop + heroCardHeight),
            title = "UPLOAD",
            value = result.upload?.let { formatSpeedValue(it, speedUnit) } ?: "-",
            unit = if (result.upload != null) unitLabel else "",
            accentColor = uploadColor,
        )

        // Metrics row: Ping | Jitter | Bufferbloat
        val metricsTop = uploadCardTop + heroCardHeight + 36f
        val metricsHeight = 224f
        val tileGap = 24f
        val tileWidth = (contentWidth - tileGap * 2f) / 3f

        val pingValue = formatLatencyReadout(result.ping)
        drawMetricTile(
            canvas = canvas,
            rect = RectF(margin, metricsTop, margin + tileWidth, metricsTop + metricsHeight),
            label = "PING",
            value = pingValue,
            unit = if (result.ping != null) "ms" else "",
            valueColor = Color.WHITE,
        )

        val jitterValue = formatJitterReadout(result.jitter)
        drawMetricTile(
            canvas = canvas,
            rect = RectF(
                margin + tileWidth + tileGap,
                metricsTop,
                margin + tileWidth * 2f + tileGap,
                metricsTop + metricsHeight,
            ),
            label = "JITTER",
            value = jitterValue,
            unit = if (result.jitter != null) "ms" else "",
            valueColor = Color.WHITE,
        )

        val gradeLabel = result.bufferbloatGrade?.name ?: "-"
        drawMetricTile(
            canvas = canvas,
            rect = RectF(
                margin + (tileWidth + tileGap) * 2f,
                metricsTop,
                margin + contentWidth,
                metricsTop + metricsHeight,
            ),
            label = "BUFFERBLOAT",
            value = gradeLabel,
            unit = if (result.bufferbloatGrade != null) "grade" else "",
            valueColor = bufferbloatGradeColor(result.bufferbloatGrade),
        )

        // Subtle divider + footer
        val footerDividerY = CARD_HEIGHT - 134f
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#232F4A")
            strokeWidth = 2f
        }
        canvas.drawLine(margin, footerDividerY, CARD_WIDTH - margin, footerDividerY, dividerPaint)

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7D8AA6")
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText("Pace · Internet Speed Test", margin, CARD_HEIGHT - 76f, footerPaint)

        return bitmap
    }

    suspend fun saveCardToCacheUri(
        context: Context,
        result: TestResult,
        speedUnit: SpeedUnit,
    ): Uri = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply {
            if (!exists()) mkdirs()
        }
        // Clean up older shared PNG files so app cache stays tiny
        cacheDir.listFiles()?.forEach { staleFile ->
            runCatching { staleFile.delete() }
        }

        val (downloadColor, uploadColor) = resolveShareCardColors(context = context)
        val outputFile = File(cacheDir, "pace_result_${System.currentTimeMillis()}.png")
        val bitmap = withContext(Dispatchers.Default) {
            renderBitmap(
                result = result,
                speedUnit = speedUnit,
                downloadColor = downloadColor,
                uploadColor = uploadColor,
            )
        }
        try {
            FileOutputStream(outputFile).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.flush()
            }
        } finally {
            bitmap.recycle()
        }

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outputFile,
        )
    }

    suspend fun shareResultCard(
        context: Context,
        result: TestResult,
        speedUnit: SpeedUnit,
        chooserTitle: String,
    ): Result<Unit> = runCatching {
        val uri = saveCardToCacheUri(
            context = context,
            result = result,
            speedUnit = speedUnit,
        )
        withContext(Dispatchers.Main) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    private fun drawBrandMark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        accentColor: Int,
    ) {
        val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            color = accentColor
        }
        canvas.drawArc(arcRect, 172f, 196f, false, arcPaint)

        val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(cx, cy + 4f, cx + radius * 0.56f, cy - radius * 0.42f, needlePaint)
        canvas.drawCircle(cx, cy + 4f, 6.5f, needlePaint)
    }

    private fun drawPillBadge(
        canvas: Canvas,
        label: String,
        rightEdge: Float,
        centerY: Float,
        bgColor: Int,
        textColor: Int,
    ) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textWidth = textPaint.measureText(label)
        val pillWidth = textWidth + 64f
        val pillHeight = 64f
        val rect = RectF(
            rightEdge - pillWidth,
            centerY - pillHeight / 2f,
            rightEdge,
            centerY + pillHeight / 2f,
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawRoundRect(rect, 32f, 32f, bgPaint)

        val fontMetrics = textPaint.fontMetrics
        val baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(label, rect.left + 32f, baseline, textPaint)
    }

    private fun drawThroughputHeroCard(
        canvas: Canvas,
        rect: RectF,
        title: String,
        value: String,
        unit: String,
        accentColor: Int,
    ) {
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#172035")
        }
        canvas.drawRoundRect(rect, 44f, 44f, cardPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#273554")
        }
        canvas.drawRoundRect(rect, 44f, 44f, borderPaint)

        // Left vertical accent pill using DownloadColor / UploadColor
        val accentRect = RectF(rect.left + 36f, rect.top + 44f, rect.left + 48f, rect.bottom - 44f)
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
        }
        canvas.drawRoundRect(accentRect, 6f, 6f, accentPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 28f
            letterSpacing = 0.12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(title, rect.left + 84f, rect.top + 88f, labelPaint)

        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 112f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val valueY = rect.top + 226f
        canvas.drawText(value, rect.left + 82f, valueY, valuePaint)

        if (unit.isNotEmpty()) {
            val valueWidth = valuePaint.measureText(value)
            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9BA7C0")
                textSize = 38f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            canvas.drawText(unit, rect.left + 104f + valueWidth, valueY - 6f, unitPaint)
        }
    }

    private fun drawMetricTile(
        canvas: Canvas,
        rect: RectF,
        label: String,
        value: String,
        unit: String,
        valueColor: Int,
    ) {
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#172035")
        }
        canvas.drawRoundRect(rect, 36f, 36f, cardPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#273554")
        }
        canvas.drawRoundRect(rect, 36f, 36f, borderPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8B99B5")
            textSize = 24f
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(label, rect.left + 32f, rect.top + 66f, labelPaint)

        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = valueColor
            textSize = 56f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText(value, rect.left + 32f, rect.top + 148f, valuePaint)

        if (unit.isNotEmpty()) {
            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8B99B5")
                textSize = 26f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            canvas.drawText(unit, rect.left + 32f, rect.top + 190f, unitPaint)
        }
    }

    private fun connectionTypeShortLabel(result: TestResult): String {
        val band = result.wifiBand?.trim()?.takeIf { it.isNotBlank() }
        val tag = result.cellularNetworkTag?.trim()?.takeIf { it.isNotBlank() }
        return when (result.connectionType) {
            ConnectionType.WIFI -> if (band != null) "Wi-Fi · $band" else "Wi-Fi"
            ConnectionType.MOBILE -> if (tag != null) "Mobile data · $tag" else "Mobile data"
            ConnectionType.ETHERNET -> "Ethernet"
            ConnectionType.NONE -> "Offline"
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }

    private fun bufferbloatGradeColor(grade: BufferbloatGrade?): Int = when (grade) {
        BufferbloatGrade.A -> Color.parseColor("#34D399")
        BufferbloatGrade.B -> Color.parseColor("#60A5FA")
        BufferbloatGrade.C -> Color.parseColor("#FBBF24")
        BufferbloatGrade.D -> Color.parseColor("#FB923C")
        BufferbloatGrade.F -> Color.parseColor("#F87171")
        null -> Color.parseColor("#9BA7C0")
    }
}
