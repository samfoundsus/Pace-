package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.models.RatingLevel
import com.example.data.models.RatingReason
import com.example.data.models.ResultEvaluator
import com.example.data.models.TestResult
import com.example.data.models.UseCaseRating
import com.example.data.models.UseCaseType

@Composable
fun EstimatedRatingsCard(
    result: TestResult,
    modifier: Modifier = Modifier,
) {
    val ratings = ResultEvaluator.evaluateUseCases(result)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("estimated_ratings_card"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.ratings_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(14.dp))

            ratings.forEachIndexed { index, rating ->
                UseCaseRatingRow(rating = rating)
                if (index < ratings.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun UseCaseRatingRow(
    rating: UseCaseRating,
) {
    val icon = when (rating.useCase) {
        UseCaseType.BROWSING -> SpeedTestIcons.Browsing
        UseCaseType.STREAMING -> SpeedTestIcons.Streaming
        UseCaseType.VIDEO_CALLS -> SpeedTestIcons.VideoCall
        UseCaseType.GAMING -> SpeedTestIcons.Gaming
    }

    val title = when (rating.useCase) {
        UseCaseType.BROWSING -> stringResource(R.string.use_case_browsing)
        UseCaseType.STREAMING -> stringResource(R.string.use_case_streaming)
        UseCaseType.VIDEO_CALLS -> stringResource(R.string.use_case_video_calls)
        UseCaseType.GAMING -> stringResource(R.string.use_case_gaming)
    }

    val reason = when (rating.reason) {
        RatingReason.BROWSING_GOOD -> stringResource(R.string.reason_browsing_good)
        RatingReason.BROWSING_OKAY -> stringResource(R.string.reason_browsing_okay)
        RatingReason.BROWSING_POOR -> stringResource(R.string.reason_browsing_poor)
        RatingReason.STREAMING_GOOD -> stringResource(R.string.reason_streaming_good)
        RatingReason.STREAMING_OKAY -> stringResource(R.string.reason_streaming_okay)
        RatingReason.STREAMING_POOR -> stringResource(R.string.reason_streaming_poor)
        RatingReason.VIDEO_CALLS_GOOD -> stringResource(R.string.reason_video_calls_good)
        RatingReason.VIDEO_CALLS_OKAY -> stringResource(R.string.reason_video_calls_okay)
        RatingReason.VIDEO_CALLS_POOR -> stringResource(R.string.reason_video_calls_poor)
        RatingReason.GAMING_GOOD -> stringResource(R.string.reason_gaming_good)
        RatingReason.GAMING_OKAY -> stringResource(R.string.reason_gaming_okay)
        RatingReason.GAMING_POOR -> stringResource(R.string.reason_gaming_poor)
        RatingReason.UNAVAILABLE -> stringResource(R.string.reason_unavailable)
    }

    val badgeLabel = when (rating.level) {
        RatingLevel.GOOD -> stringResource(R.string.rating_good)
        RatingLevel.OKAY -> stringResource(R.string.rating_okay)
        RatingLevel.POOR -> stringResource(R.string.rating_poor)
    }

    val badgeBg = when (rating.level) {
        RatingLevel.GOOD -> MaterialTheme.colorScheme.tertiaryContainer
        RatingLevel.OKAY -> MaterialTheme.colorScheme.secondaryContainer
        RatingLevel.POOR -> MaterialTheme.colorScheme.errorContainer
    }

    val badgeFg = when (rating.level) {
        RatingLevel.GOOD -> MaterialTheme.colorScheme.onTertiaryContainer
        RatingLevel.OKAY -> MaterialTheme.colorScheme.onSecondaryContainer
        RatingLevel.POOR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rating_row_${rating.useCase.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = badgeBg,
        ) {
            Text(
                text = badgeLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = badgeFg,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}
