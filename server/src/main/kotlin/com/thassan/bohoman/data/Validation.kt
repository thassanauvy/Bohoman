package com.thassan.bohoman.data

import com.thassan.bohoman.contracts.Deadline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message)

internal fun invalid(message: String): Nothing = throw ApiException(400, "validation_error", message)

internal fun validateTitle(title: String): String = title.trim().also {
    if (it.isEmpty() || it.length > 200) invalid("Title must contain 1 to 200 characters")
    if ('\u0000' in it) invalid("Title must not contain null characters")
}

internal fun validateDescription(description: String?) {
    if (description != null && description.length > 10_000) invalid("Description is limited to 10000 characters")
    if (description != null && '\u0000' in description) invalid("Description must not contain null characters")
}

internal fun validatePriority(priority: Int) {
    if (priority !in 0..3) invalid("Priority must be between 0 and 3")
}

internal fun validateVersion(version: Long) {
    if (version < 1) invalid("expectedVersion must be positive")
}

internal fun validatePage(limit: Int, offset: Int) {
    if (limit !in 1..100) invalid("limit must be between 1 and 100")
    if (offset !in 0..1_000_000) invalid("offset must be between 0 and 1000000")
}

internal fun validateDeadline(value: Deadline?) {
    if (value == null) return
    val rawDate = value.date
    val rawInstant = value.instant
    if ((rawDate == null) == (rawInstant == null)) invalid("Specify exactly one date or instant")
    if (rawDate != null) {
        if (value.timeZone != null) invalid("Date-only deadlines must not include a timezone")
        val date = try { LocalDate.parse(rawDate) } catch (_: Exception) {
            invalid("date must be an ISO calendar date (YYYY-MM-DD)")
        }
        if (date.year !in 1..9999 || !Regex("\\d{4}-\\d{2}-\\d{2}").matches(rawDate)) {
            invalid("date must be an ISO calendar date (YYYY-MM-DD)")
        }
    } else {
        val instant = try { Instant.parse(rawInstant) } catch (_: Exception) {
            invalid("instant must be an ISO instant with a UTC offset")
        }
        if (instant.atOffset(ZoneOffset.UTC).year !in 1..9999) invalid("instant must have a year from 0001 to 9999")
        if (value.timeZone !in ZoneId.getAvailableZoneIds()) invalid("An exact deadline requires a valid IANA timezone")
    }
}
