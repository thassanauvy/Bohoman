package com.thassan.bohoman.core.time

import com.thassan.bohoman.contracts.Deadline
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** An all-day date is displayed unchanged in every zone; only the days remaining use the viewer's today. */
fun deadlineLabel(deadline: Deadline): String {
    deadline.date?.let { return it }
    val local = Instant.parse(requireNotNull(deadline.instant)).toLocalDateTime(TimeZone.of(requireNotNull(deadline.timeZone)))
    return "${local.date} · ${local.time} · ${deadline.timeZone}"
}

fun countdownLabel(deadline: Deadline, now: Instant, viewerZone: TimeZone): String {
    deadline.date?.let {
        val days = now.toLocalDateTime(viewerZone).date.daysUntil(LocalDate.parse(it))
        return when {
            days == 0 -> "Today"
            days == 1 -> "Tomorrow"
            days > 1 -> "In $days days"
            days == -1 -> "1 day ago"
            else -> "${-days} days ago"
        }
    }
    val remaining = Instant.parse(requireNotNull(deadline.instant)) - now
    val minutes = remaining.inWholeMinutes
    if (remaining.isPositive() && minutes == 0L) return "Less than a minute"
    if (remaining.isNegative() && minutes == 0L) return "Just passed"
    if (remaining.inWholeMilliseconds == 0L) return "Now"
    val absoluteMinutes = kotlin.math.abs(minutes)
    val days = absoluteMinutes / (24 * 60)
    val hours = (absoluteMinutes % (24 * 60)) / 60
    val label = when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${absoluteMinutes % 60}m"
        else -> "${absoluteMinutes}m"
    }
    return if (remaining.isPositive()) "In $label" else "$label ago"
}

/** UI validation; the server remains authoritative. Never invent midnight for date-only input. */
fun parseDeadline(date: String, instant: String, zone: String, required: Boolean): Deadline? {
    val cleanDate = date.trim()
    val cleanInstant = instant.trim()
    val cleanZone = zone.trim()
    require(cleanDate.isEmpty() || cleanInstant.isEmpty()) { "Choose a calendar date or an exact instant, not both." }
    if (cleanDate.isNotEmpty()) {
        require(cleanZone.isEmpty()) { "A calendar date does not need a timezone." }
        val parsed = LocalDate.parse(cleanDate)
        require(parsed.year in 1..9999 && Regex("\\d{4}-\\d{2}-\\d{2}").matches(cleanDate)) {
            "Enter a calendar date from 0001-01-01 to 9999-12-31."
        }
        return Deadline(date = parsed.toString())
    }
    if (cleanInstant.isNotEmpty()) {
        val parsed = Instant.parse(cleanInstant)
        require(parsed.toLocalDateTime(TimeZone.UTC).year in 1..9999) {
            "Enter an instant with a year from 0001 to 9999."
        }
        require(cleanZone in TimeZone.availableZoneIds) { "Enter a valid IANA timezone, such as Asia/Dhaka." }
        return Deadline(instant = parsed.toString(), timeZone = cleanZone)
    }
    require(cleanZone.isEmpty()) { "Add an exact instant or clear the timezone." }
    require(!required) { "A countdown needs a target date or instant." }
    return null
}

/** Preserve the original instant when editing an overlapping daylight-saving local time.
 * Nonexistent local times are rejected instead of being silently moved forward. */
fun parseLocalDeadline(localDateTime: String, zone: String, original: Deadline? = null): Deadline {
    val cleanZone = zone.trim()
    require(cleanZone in TimeZone.availableZoneIds) { "Enter a valid IANA timezone, such as Asia/Dhaka." }
    val timeZone = TimeZone.of(cleanZone)
    val local = LocalDateTime.parse(localDateTime.trim())
    val originalInstant = original?.instant?.let(Instant::parse)
    val instant = if (original?.timeZone == cleanZone && originalInstant?.toLocalDateTime(timeZone) == local) {
        requireNotNull(originalInstant)
    } else {
        local.toInstant(timeZone)
    }
    require(instant.toLocalDateTime(timeZone) == local) {
        "That local time does not exist in $cleanZone because the clocks change. Choose another time."
    }
    return requireNotNull(parseDeadline("", instant.toString(), cleanZone, required = true))
}
