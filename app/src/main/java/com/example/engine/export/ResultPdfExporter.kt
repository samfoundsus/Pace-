package com.example.engine.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import com.example.R
import com.example.data.models.BufferbloatGrade
import com.example.data.models.ConnectionType
import com.example.data.models.RatingLevel
import com.example.data.models.RatingReason
import com.example.data.models.ResultEvaluator
import com.example.data.models.TestResult
import com.example.data.models.UseCaseType
import com.example.data.settings.SpeedUnit
import com.example.ui.components.formatJitterReadout
import com.example.ui.components.formatLatencyReadout
import com.example.ui.components.formatSpeedValue
import com.example.ui.components.formatTimestampMedium
import com.example.ui.theme.PixelBlue40
import com.example.ui.theme.PixelTeal40
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates a single A4 PDF summary (595 x 842 pt) using Android's built-in PdfDocument API.
 * Network name (Wi-Fi SSID / carrier) is omitted unless explicitly opted in by the user.
 */
object ResultPdfExporter {

    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842

    fun suggestedFileName(timestampMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US)
        return "Pace_SpeedTest_${formatter.format(Date(timestampMs))}.pdf"
    }

    fun createPdfBytes(
        context: Context,
        result: TestResult,
        speedUnit: SpeedUnit,
        includeNetworkName: Boolean,
    ): ByteArray {
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = document.startPage(pageInfo)
            drawPage(
                context = context,
                canvas = page.canvas,
                result = result,
                speedUnit = speedUnit,
                includeNetworkName = includeNetworkName,
            )
            document.finishPage(page)

            val output = ByteArrayOutputStream()
            document.writeTo(output)
            return output.toByteArray()
        } finally {
            document.close()
        }
    }

    suspend fun writePdfToUri(
        context: Context,
        uri: Uri,
        result: TestResult,
        speedUnit: SpeedUnit,
        includeNetworkName: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val pdfBytes = createPdfBytes(
                context = context,
                result = result,
                speedUnit = speedUnit,
                includeNetworkName = includeNetworkName,
            )
            val stream = context.contentResolver.openOutputStream(uri)
                ?: error("Could not open destination document for writing")
            stream.use { output ->
                output.write(pdfBytes)
                output.flush()
            }
        }
    }

    private fun drawPage(
        context: Context,
        canvas: Canvas,
        result: TestResult,
        speedUnit: SpeedUnit,
        includeNetworkName: Boolean,
    ) {
        val margin = 44f
        val contentWidth = PAGE_WIDTH - margin * 2f

        // White page background
        canvas.drawColor(Color.WHITE)

        val primaryText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111827")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1F2937")
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val mutedText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6B7280")
            textSize = 10.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val monoBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111827")
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val sectionTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PixelBlue40.toArgb()
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB")
            strokeWidth = 1f
        }
        val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
        }
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.parseColor("#E2E8F0")
        }

        // Top accent bar
        val accentBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PixelBlue40.toArgb()
        }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 6f, accentBarPaint)

        // Header
        var y = 52f
        primaryText.textSize = 22f
        canvas.drawText("Pace", margin, y, primaryText)

        mutedText.textSize = 11f
        canvas.drawText("Internet Speed Test Report", margin + 62f, y - 2f, mutedText)

        val dateText = formatTimestampMedium(result.timestamp)
        val dateWidth = mutedText.measureText(dateText)
        canvas.drawText(dateText, PAGE_WIDTH - margin - dateWidth, y - 2f, mutedText)

        y += 18f
        canvas.drawLine(margin, y, PAGE_WIDTH - margin, y, dividerPaint)

        // Connection summary bar
        y += 22f
        val wifiBand = result.wifiBand?.trim()?.takeIf { it.isNotBlank() }
        val cellularTag = result.cellularNetworkTag?.trim()?.takeIf { it.isNotBlank() }
        val connectionTypeLabel = when (result.connectionType) {
            ConnectionType.WIFI -> {
                val base = context.getString(R.string.connection_wifi)
                if (wifiBand != null) "$base · $wifiBand" else base
            }
            ConnectionType.MOBILE -> {
                val base = context.getString(R.string.connection_mobile)
                if (cellularTag != null) "$base · $cellularTag" else base
            }
            ConnectionType.ETHERNET -> context.getString(R.string.connection_ethernet)
            ConnectionType.NONE -> context.getString(R.string.connection_none)
        }
        val connectionLine = buildString {
            append("Connection: ")
            append(connectionTypeLabel)
            if (includeNetworkName && !result.networkName.isNullOrBlank()) {
                when (result.connectionType) {
                    ConnectionType.WIFI -> append("  ·  Wi-Fi: ${result.networkName}")
                    ConnectionType.MOBILE -> append("  ·  Carrier: ${result.networkName}")
                    else -> append("  ·  ${result.networkName}")
                }
            }
        }
        bodyText.textSize = 11.5f
        canvas.drawText(connectionLine, margin, y, bodyText)

        val serverName = result.serverName?.trim()?.takeIf { it.isNotEmpty() }
        if (serverName != null) {
            val serverLocation = result.serverLocation?.trim()?.takeIf { it.isNotEmpty() }
            val serverLine = if (serverLocation != null) {
                "Server: $serverName · $serverLocation"
            } else {
                "Server: $serverName"
            }
            y += 16f
            canvas.drawText(serverLine, margin, y, bodyText)
        }

        // Throughput cards (Download & Upload)
        y += 24f
        canvas.drawText("THROUGHPUT", margin, y, sectionTitle)
        y += 12f

        val unitLabel = when (speedUnit) {
            SpeedUnit.MBPS -> context.getString(R.string.unit_mbps)
            SpeedUnit.MB_PER_SEC -> context.getString(R.string.unit_mb_per_sec)
        }
        val halfWidth = (contentWidth - 16f) / 2f
        val throughputHeight = 84f

        val dlRect = RectF(margin, y, margin + halfWidth, y + throughputHeight)
        val ulRect = RectF(margin + halfWidth + 16f, y, margin + contentWidth, y + throughputHeight)

        drawPdfHeroCard(
            canvas = canvas,
            rect = dlRect,
            label = "Download",
            value = result.download?.let { formatSpeedValue(it, speedUnit) } ?: "-",
            unit = if (result.download != null) unitLabel else "",
            valueColor = PixelBlue40.toArgb(),
            cardFill = cardFill,
            cardStroke = cardStroke,
            mutedText = mutedText,
            monoBold = monoBold,
        )
        drawPdfHeroCard(
            canvas = canvas,
            rect = ulRect,
            label = "Upload",
            value = result.upload?.let { formatSpeedValue(it, speedUnit) } ?: "-",
            unit = if (result.upload != null) unitLabel else "",
            valueColor = PixelTeal40.toArgb(),
            cardFill = cardFill,
            cardStroke = cardStroke,
            mutedText = mutedText,
            monoBold = monoBold,
        )

        // Latency & Bufferbloat section
        y += throughputHeight + 28f
        canvas.drawText("LATENCY & BUFFERBLOAT", margin, y, sectionTitle)
        y += 12f

        val latencyCardRect = RectF(margin, y, margin + contentWidth, y + 148f)
        canvas.drawRoundRect(latencyCardRect, 10f, 10f, cardFill)
        canvas.drawRoundRect(latencyCardRect, 10f, 10f, cardStroke)

        val rows = listOf(
            "Idle ping" to formatMsOrUnavailable(result.ping, isJitter = false),
            "Jitter" to formatMsOrUnavailable(result.jitter, isJitter = true),
            "Download loaded latency" to formatMsOrUnavailable(result.downloadLoadedLatency, isJitter = false),
            "Upload loaded latency" to formatMsOrUnavailable(result.uploadLoadedLatency, isJitter = false),
            "Bufferbloat grade" to (result.bufferbloatGrade?.let { "Grade ${it.name}" } ?: "Unavailable"),
        )
        var rowY = y + 26f
        rows.forEachIndexed { index, (label, value) ->
            canvas.drawText(label, margin + 16f, rowY, bodyText)
            monoBold.textSize = 11.5f
            monoBold.color = if (index == rows.lastIndex) {
                gradeInkColor(result.bufferbloatGrade)
            } else {
                Color.parseColor("#111827")
            }
            val valWidth = monoBold.measureText(value)
            canvas.drawText(value, margin + contentWidth - 16f - valWidth, rowY, monoBold)
            if (index < rows.lastIndex) {
                canvas.drawLine(
                    margin + 16f,
                    rowY + 9f,
                    margin + contentWidth - 16f,
                    rowY + 9f,
                    dividerPaint,
                )
            }
            rowY += 27f
        }
        monoBold.color = Color.parseColor("#111827")

        // Use-case ratings section
        y = latencyCardRect.bottom + 28f
        canvas.drawText("ESTIMATED EXPERIENCE", margin, y, sectionTitle)
        y += 12f

        val ratings = ResultEvaluator.evaluateUseCases(result)
        val ratingsCardHeight = 204f
        val ratingsRect = RectF(margin, y, margin + contentWidth, y + ratingsCardHeight)
        canvas.drawRoundRect(ratingsRect, 10f, 10f, cardFill)
        canvas.drawRoundRect(ratingsRect, 10f, 10f, cardStroke)

        var ratingY = y + 28f
        ratings.forEachIndexed { index, rating ->
            val useCaseTitle = when (rating.useCase) {
                UseCaseType.BROWSING -> context.getString(R.string.use_case_browsing)
                UseCaseType.STREAMING -> context.getString(R.string.use_case_streaming)
                UseCaseType.VIDEO_CALLS -> context.getString(R.string.use_case_video_calls)
                UseCaseType.GAMING -> context.getString(R.string.use_case_gaming)
            }
            val levelLabel = when (rating.level) {
                RatingLevel.GOOD -> context.getString(R.string.rating_good)
                RatingLevel.OKAY -> context.getString(R.string.rating_okay)
                RatingLevel.POOR -> context.getString(R.string.rating_poor)
            }
            val reasonText = reasonString(context, rating.reason)

            primaryText.textSize = 12f
            canvas.drawText(useCaseTitle, margin + 16f, ratingY, primaryText)

            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ratingInkColor(rating.level)
                textSize = 11.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val levelWidth = badgePaint.measureText(levelLabel)
            canvas.drawText(levelLabel, margin + contentWidth - 16f - levelWidth, ratingY, badgePaint)

            mutedText.textSize = 10f
            canvas.drawText(reasonText, margin + 16f, ratingY + 15f, mutedText)

            if (index < ratings.lastIndex) {
                canvas.drawLine(
                    margin + 16f,
                    ratingY + 25f,
                    margin + contentWidth - 16f,
                    ratingY + 25f,
                    dividerPaint,
                )
            }
            ratingY += 48f
        }

        // Footer
        val footerY = PAGE_HEIGHT - 42f
        canvas.drawLine(margin, footerY - 16f, PAGE_WIDTH - margin, footerY - 16f, dividerPaint)
        mutedText.textSize = 9.5f
        canvas.drawText("Pace - Internet Speed Test", margin, footerY, mutedText)
        val pageLabel = "Page 1 of 1"
        val pageLabelWidth = mutedText.measureText(pageLabel)
        canvas.drawText(pageLabel, PAGE_WIDTH - margin - pageLabelWidth, footerY, mutedText)
    }

    private fun drawPdfHeroCard(
        canvas: Canvas,
        rect: RectF,
        label: String,
        value: String,
        unit: String,
        valueColor: Int,
        cardFill: Paint,
        cardStroke: Paint,
        mutedText: Paint,
        monoBold: Paint,
    ) {
        canvas.drawRoundRect(rect, 10f, 10f, cardFill)
        canvas.drawRoundRect(rect, 10f, 10f, cardStroke)

        mutedText.textSize = 10.5f
        canvas.drawText(label, rect.left + 16f, rect.top + 24f, mutedText)

        monoBold.textSize = 28f
        monoBold.color = valueColor
        canvas.drawText(value, rect.left + 16f, rect.top + 62f, monoBold)

        if (unit.isNotEmpty()) {
            val valueWidth = monoBold.measureText(value)
            mutedText.textSize = 11.5f
            canvas.drawText(unit, rect.left + 22f + valueWidth, rect.top + 60f, mutedText)
        }
    }

    private fun formatMsOrUnavailable(valueMs: Double?, isJitter: Boolean): String {
        if (valueMs == null || !valueMs.isFinite() || valueMs < 0.0) return "Unavailable"
        val formatted = if (isJitter) formatJitterReadout(valueMs) else formatLatencyReadout(valueMs)
        return "$formatted ms"
    }

    private fun gradeInkColor(grade: BufferbloatGrade?): Int = when (grade) {
        BufferbloatGrade.A -> Color.parseColor("#047857")
        BufferbloatGrade.B -> Color.parseColor("#1D4ED8")
        BufferbloatGrade.C -> Color.parseColor("#B45309")
        BufferbloatGrade.D -> Color.parseColor("#C2410C")
        BufferbloatGrade.F -> Color.parseColor("#B91C1C")
        null -> Color.parseColor("#6B7280")
    }

    private fun ratingInkColor(level: RatingLevel): Int = when (level) {
        RatingLevel.GOOD -> Color.parseColor("#047857")
        RatingLevel.OKAY -> Color.parseColor("#B45309")
        RatingLevel.POOR -> Color.parseColor("#B91C1C")
    }

    private fun reasonString(context: Context, reason: RatingReason): String = when (reason) {
        RatingReason.BROWSING_GOOD -> context.getString(R.string.reason_browsing_good)
        RatingReason.BROWSING_OKAY -> context.getString(R.string.reason_browsing_okay)
        RatingReason.BROWSING_POOR -> context.getString(R.string.reason_browsing_poor)
        RatingReason.STREAMING_GOOD -> context.getString(R.string.reason_streaming_good)
        RatingReason.STREAMING_OKAY -> context.getString(R.string.reason_streaming_okay)
        RatingReason.STREAMING_POOR -> context.getString(R.string.reason_streaming_poor)
        RatingReason.VIDEO_CALLS_GOOD -> context.getString(R.string.reason_video_calls_good)
        RatingReason.VIDEO_CALLS_OKAY -> context.getString(R.string.reason_video_calls_okay)
        RatingReason.VIDEO_CALLS_POOR -> context.getString(R.string.reason_video_calls_poor)
        RatingReason.GAMING_GOOD -> context.getString(R.string.reason_gaming_good)
        RatingReason.GAMING_OKAY -> context.getString(R.string.reason_gaming_okay)
        RatingReason.GAMING_POOR -> context.getString(R.string.reason_gaming_poor)
        RatingReason.UNAVAILABLE -> context.getString(R.string.reason_unavailable)
    }
}
