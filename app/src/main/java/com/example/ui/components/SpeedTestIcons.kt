package com.example.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight custom vector icons so the app never needs material-icons-extended.
 */
object SpeedTestIcons {

    val Gauge: ImageVector by lazy {
        ImageVector.Builder(
            name = "Gauge",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.64f, 18.36f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18.36f, 18.36f)
            }
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(12f, 13f)
                lineTo(16.2f, 8.8f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 11.5f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 11.5f)
                close()
            }
        }.build()
    }

    val History: ImageVector by lazy {
        ImageVector.Builder(
            name = "History",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3f, 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = false, 6f, 5.3f)
                moveTo(3f, 4f)
                verticalLineTo(9f)
                horizontalLineTo(8f)
            }
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 7f)
                verticalLineTo(12f)
                lineTo(15.5f, 14f)
            }
        }.build()
    }

    val Tools: ImageVector by lazy {
        ImageVector.Builder(
            name = "Tools",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 7f)
                horizontalLineTo(20f)
                moveTo(4f, 17f)
                horizontalLineTo(20f)
                moveTo(9f, 4f)
                verticalLineTo(10f)
                moveTo(15f, 14f)
                verticalLineTo(20f)
            }
        }.build()
    }

    val Wifi: ImageVector by lazy {
        ImageVector.Builder(
            name = "Wifi",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(3.5f, 9.5f)
                arcTo(12.5f, 12.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20.5f, 9.5f)
                moveTo(6.8f, 13f)
                arcTo(7.8f, 7.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17.2f, 13f)
                moveTo(10f, 16.3f)
                arcTo(3.2f, 3.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 14f, 16.3f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 18.3f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 18.3f)
                close()
            }
        }.build()
    }

    val MobileData: ImageVector by lazy {
        ImageVector.Builder(
            name = "MobileData",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(5f, 19f)
                verticalLineTo(15f)
                moveTo(9.5f, 19f)
                verticalLineTo(12f)
                moveTo(14f, 19f)
                verticalLineTo(8.5f)
                moveTo(18.5f, 19f)
                verticalLineTo(5f)
            }
        }.build()
    }

    val Ethernet: ImageVector by lazy {
        ImageVector.Builder(
            name = "Ethernet",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 5f)
                horizontalLineTo(19f)
                verticalLineTo(15f)
                horizontalLineTo(15f)
                verticalLineTo(19f)
                horizontalLineTo(9f)
                verticalLineTo(15f)
                horizontalLineTo(5f)
                close()
                moveTo(9f, 8f)
                verticalLineTo(11f)
                moveTo(12f, 8f)
                verticalLineTo(11f)
                moveTo(15f, 8f)
                verticalLineTo(11f)
            }
        }.build()
    }

    val Offline: ImageVector by lazy {
        ImageVector.Builder(
            name = "Offline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(3f, 3f)
                lineTo(21f, 21f)
                moveTo(8.5f, 14.5f)
                arcTo(5.5f, 5.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 13.5f, 13.4f)
                moveTo(4.2f, 10.2f)
                arcTo(11.5f, 11.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9.4f, 7.6f)
                moveTo(14.5f, 7.7f)
                arcTo(11.5f, 11.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 19.8f, 10.2f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 18.2f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 18.2f)
                close()
            }
        }.build()
    }

    val ArrowDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowDown",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 4.5f)
                verticalLineTo(18.5f)
                moveTo(6.5f, 13.5f)
                lineTo(12f, 19f)
                lineTo(17.5f, 13.5f)
            }
        }.build()
    }

    val ArrowUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowUp",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 19.5f)
                verticalLineTo(5.5f)
                moveTo(6.5f, 10.5f)
                lineTo(12f, 5f)
                lineTo(17.5f, 10.5f)
            }
        }.build()
    }

    val Ping: ImageVector by lazy {
        ImageVector.Builder(
            name = "Ping",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7f, 16f)
                lineTo(3.5f, 12.5f)
                lineTo(7f, 9f)
                moveTo(4f, 12.5f)
                horizontalLineTo(14f)
                moveTo(17f, 8f)
                lineTo(20.5f, 11.5f)
                lineTo(17f, 15f)
                moveTo(20f, 11.5f)
                horizontalLineTo(10f)
            }
        }.build()
    }

    val Jitter: ImageVector by lazy {
        ImageVector.Builder(
            name = "Jitter",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3f, 12f)
                horizontalLineTo(6.5f)
                lineTo(9f, 6.5f)
                lineTo(12.5f, 17.5f)
                lineTo(15.5f, 9.5f)
                lineTo(17.5f, 12f)
                horizontalLineTo(21f)
            }
        }.build()
    }

    val Stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "Stop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 6f)
                horizontalLineTo(17f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18f, 7f)
                verticalLineTo(17f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17f, 18f)
                horizontalLineTo(7f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6f, 17f)
                verticalLineTo(7f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7f, 6f)
                close()
            }
        }.build()
    }

    val Bufferbloat: ImageVector by lazy {
        ImageVector.Builder(
            name = "Bufferbloat",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 17f)
                horizontalLineTo(20f)
                moveTo(4f, 12f)
                horizontalLineTo(15f)
                moveTo(4f, 7f)
                horizontalLineTo(10f)
                moveTo(17f, 6f)
                lineTo(20f, 9f)
                lineTo(17f, 12f)
            }
        }.build()
    }

    val Browsing: ImageVector by lazy {
        ImageVector.Builder(
            name = "Browsing",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 3f)
                moveTo(3.5f, 12f)
                horizontalLineTo(20.5f)
                moveTo(12f, 3f)
                curveTo(14.8f, 6f, 14.8f, 18f, 12f, 21f)
                moveTo(12f, 3f)
                curveTo(9.2f, 6f, 9.2f, 18f, 12f, 21f)
            }
        }.build()
    }

    val Streaming: ImageVector by lazy {
        ImageVector.Builder(
            name = "Streaming",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 6f)
                horizontalLineTo(20f)
                verticalLineTo(16f)
                horizontalLineTo(4f)
                close()
                moveTo(9f, 20f)
                horizontalLineTo(15f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(10.2f, 8.6f)
                lineTo(15f, 11f)
                lineTo(10.2f, 13.4f)
                close()
            }
        }.build()
    }

    val VideoCall: ImageVector by lazy {
        ImageVector.Builder(
            name = "VideoCall",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 7f)
                horizontalLineTo(15f)
                verticalLineTo(17f)
                horizontalLineTo(4f)
                close()
                moveTo(15f, 10.5f)
                lineTo(20f, 7.8f)
                verticalLineTo(16.2f)
                lineTo(15f, 13.5f)
            }
        }.build()
    }

    val Gaming: ImageVector by lazy {
        ImageVector.Builder(
            name = "Gaming",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.5f, 8.5f)
                horizontalLineTo(17.5f)
                curveTo(19.5f, 8.5f, 21f, 10.2f, 21f, 12.8f)
                curveTo(21f, 15.2f, 19.6f, 17.2f, 18f, 17.2f)
                curveTo(16.8f, 17.2f, 16f, 16.3f, 15.2f, 15.2f)
                horizontalLineTo(8.8f)
                curveTo(8f, 16.3f, 7.2f, 17.2f, 6f, 17.2f)
                curveTo(4.4f, 17.2f, 3f, 15.2f, 3f, 12.8f)
                curveTo(3f, 10.2f, 4.5f, 8.5f, 6.5f, 8.5f)
                close()
                moveTo(7.5f, 11f)
                verticalLineTo(14f)
                moveTo(6f, 12.5f)
                horizontalLineTo(9f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(15.5f, 11.2f)
                arcTo(0.9f, 0.9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15.49f, 11.2f)
                close()
                moveTo(17.7f, 13f)
                arcTo(0.9f, 0.9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.69f, 13f)
                close()
            }
        }.build()
    }

    val PdfDocument: ImageVector by lazy {
        ImageVector.Builder(
            name = "PdfDocument",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 3f)
                horizontalLineTo(14f)
                lineTo(19f, 8f)
                verticalLineTo(20f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18f, 21f)
                horizontalLineTo(6f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5f, 20f)
                verticalLineTo(4f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6f, 3f)
                close()
                moveTo(14f, 3f)
                verticalLineTo(8f)
                horizontalLineTo(19f)
                moveTo(8.5f, 13f)
                horizontalLineTo(15.5f)
                moveTo(8.5f, 16.5f)
                horizontalLineTo(13.5f)
            }
        }.build()
    }

    val Server: ImageVector by lazy {
        ImageVector.Builder(
            name = "Server",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 5f)
                horizontalLineTo(20f)
                verticalLineTo(10f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 14f)
                horizontalLineTo(20f)
                verticalLineTo(19f)
                horizontalLineTo(4f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(7.5f, 6.6f)
                arcTo(0.9f, 0.9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7.49f, 6.6f)
                close()
                moveTo(7.5f, 15.6f)
                arcTo(0.9f, 0.9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7.49f, 15.6f)
                close()
            }
        }.build()
    }

    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronRight",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.5f, 6.5f)
                lineTo(15f, 12f)
                lineTo(9.5f, 17.5f)
            }
        }.build()
    }

    val Copy: ImageVector by lazy {
        ImageVector.Builder(
            name = "Copy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 9f)
                horizontalLineTo(19f)
                verticalLineTo(20f)
                horizontalLineTo(9f)
                close()
                moveTo(15f, 9f)
                verticalLineTo(5f)
                horizontalLineTo(5f)
                verticalLineTo(16f)
                horizontalLineTo(9f)
            }
        }.build()
    }
}
