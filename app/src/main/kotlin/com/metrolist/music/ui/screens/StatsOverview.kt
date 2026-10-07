/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.DailyListening
import com.metrolist.music.db.entities.SongWithStats
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_DAILY_BARS = 62
private val MAX_BAR_WIDTH = 20.dp

data class ChartBucket(
    val label: String,
    val minutes: Float,
)

data class ChartData(
    val buckets: List<ChartBucket>,
    val average: Float,
    val isMonthly: Boolean,
)

/**
 * Builds the bars for the listening graph. Short ranges get one bar per day, longer ones one per month.
 * Days before the very first play are skipped so they don't drag the average down.
 */
fun buildChartData(
    rows: List<DailyListening>,
    from: LocalDateTime,
    to: LocalDateTime,
    firstEvent: LocalDateTime?,
): ChartData? {
    if (rows.isEmpty()) return null
    val firstDay = firstEvent?.toLocalDate() ?: LocalDate.parse(rows.first().day)
    val start = maxOf(from.toLocalDate(), firstDay)
    val end = to.toLocalDate()
    if (end.isBefore(start)) return null

    val minutesByDay = rows.associate { LocalDate.parse(it.day) to it.playTime / 60000f }
    val spanDays = ChronoUnit.DAYS.between(start, end) + 1
    val isMonthly = spanDays > MAX_DAILY_BARS

    val buckets =
        if (!isMonthly) {
            (0 until spanDays.toInt()).map { i ->
                ChartBucket((i + 1).toString(), minutesByDay[start.plusDays(i.toLong())] ?: 0f)
            }
        } else {
            val minutesByMonth =
                minutesByDay.entries
                    .groupBy({ YearMonth.from(it.key) }, { it.value })
                    .mapValues { (_, values) -> values.sum() }
            generateSequence(YearMonth.from(start)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.from(end)) }
                .map { month ->
                    ChartBucket(
                        month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        minutesByMonth[month] ?: 0f,
                    )
                }.toList()
        }

    return ChartData(
        buckets = buckets,
        average = buckets.sumOf { it.minutes.toDouble() }.toFloat() / buckets.size,
        isMonthly = isMonthly,
    )
}

private fun formatCompact(value: Float): String =
    when {
        value >= 1000f -> "%.2fk".format(value / 1000f)
        value == value.toInt().toFloat() -> value.toInt().toString()
        else -> "%.1f".format(value)
    }

@Composable
private fun StatsContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
fun TimeListenedCard(
    totalTimeMs: Long,
    totalPlays: Int,
    uniqueSongs: Int,
    uniqueArtists: Int,
    modifier: Modifier = Modifier,
) {
    StatsContainer(modifier) {
        Column(modifier = Modifier.padding(20.dp)) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.stats_total_time_listened),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = makeTimeString(totalTimeMs).ifEmpty { "0:00" },
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryStat(totalPlays.toString(), stringResource(R.string.stats_total_plays), Modifier.weight(1f))
                SummaryStat(uniqueSongs.toString(), stringResource(R.string.stats_unique_songs), Modifier.weight(1f))
                SummaryStat(uniqueArtists.toString(), stringResource(R.string.stats_unique_artists), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ListeningGraphCard(
    data: ChartData,
    modifier: Modifier = Modifier,
) {
    StatsContainer(modifier) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        stringResource(
                            if (data.isMonthly) R.string.stats_monthly_average else R.string.stats_daily_average,
                        ) + ": ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.stats_minutes_short, data.average.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            ListeningBarChart(data)
        }
    }
}

@Composable
private fun ListeningBarChart(data: ChartData) {
    val barColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val averageStyle = labelStyle.copy(color = lineColor, fontWeight = FontWeight.Bold)
    val textMeasurer = rememberTextMeasurer()
    val maxValue = max(data.buckets.maxOf { it.minutes }, 1f)

    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(200.dp),
    ) {
        val rightPadding = 44.dp.toPx()
        val bottomPadding = 22.dp.toPx()
        val chartWidth = size.width - rightPadding
        val chartHeight = size.height - bottomPadding

        listOf(1f, 0.5f, 0f).forEach { fraction ->
            val y = chartHeight * (1f - fraction)
            drawLine(gridColor, Offset(0f, y), Offset(chartWidth, y), strokeWidth = 1.dp.toPx())
            // Skip the middle label when it would sit on top of the average label.
            if (fraction == 0.5f && abs(data.average / maxValue - 0.5f) < 0.08f) return@forEach
            val layout = textMeasurer.measure(formatCompact(maxValue * fraction), labelStyle)
            drawText(
                layout,
                topLeft =
                    Offset(
                        chartWidth + 8.dp.toPx(),
                        (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height),
                    ),
            )
        }

        val count = data.buckets.size
        val slot = chartWidth / count
        val barWidth = minOf(slot * 0.5f, MAX_BAR_WIDTH.toPx())
        data.buckets.forEachIndexed { index, bucket ->
            val barHeight = chartHeight * (bucket.minutes / maxValue)
            if (barHeight > 0f) {
                drawRoundRect(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(barColor, barColor.copy(alpha = 0f)),
                            startY = chartHeight - barHeight,
                            endY = chartHeight,
                        ),
                    topLeft = Offset(index * slot + (slot - barWidth) / 2f, chartHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }

        val averageY = chartHeight * (1f - data.average / maxValue)
        drawLine(lineColor, Offset(0f, averageY), Offset(chartWidth, averageY), strokeWidth = 2.dp.toPx())
        val averageLayout = textMeasurer.measure(formatCompact(data.average), averageStyle)
        drawText(
            averageLayout,
            topLeft = Offset(chartWidth + 8.dp.toPx(), (averageY - averageLayout.size.height - 2.dp.toPx()).coerceAtLeast(0f)),
        )

        val labelStep = if (data.isMonthly) max(1, count / 5) else 7
        for (index in 0 until count step labelStep) {
            val layout = textMeasurer.measure(data.buckets[index].label, labelStyle)
            val x = (index * slot + slot / 2f - layout.size.width / 2f).coerceIn(0f, chartWidth - layout.size.width)
            drawText(layout, topLeft = Offset(x, chartHeight + 4.dp.toPx()))
        }
    }
}

@Composable
fun BreakdownSection(
    artist: Artist?,
    song: SongWithStats?,
    onArtistClick: () -> Unit,
    onSongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artist == null && song == null) return

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.stats_breakdown),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        )
        StatsContainer {
            Column {
                if (artist != null) {
                    BreakdownRow(
                        label = stringResource(R.string.stats_favorite_artist),
                        title = artist.artist.name,
                        subtitle =
                            joinByBullet(
                                pluralStringResource(R.plurals.n_song, artist.songCount, artist.songCount),
                                makeTimeString(artist.timeListened?.toLong()),
                            ),
                        thumbnailUrl = artist.artist.thumbnailUrl,
                        circular = true,
                        onClick = onArtistClick,
                    )
                }
                if (artist != null && song != null) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
                if (song != null) {
                    BreakdownRow(
                        label = stringResource(R.string.stats_favorite_song),
                        title = song.title,
                        subtitle =
                            joinByBullet(
                                pluralStringResource(R.plurals.n_time, song.songCountListened, song.songCountListened),
                                makeTimeString(song.timeListened),
                            ),
                        thumbnailUrl = song.thumbnailUrl,
                        circular = false,
                        onClick = onSongClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    circular: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(20.dp),
    ) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(if (circular) CircleShape else RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
