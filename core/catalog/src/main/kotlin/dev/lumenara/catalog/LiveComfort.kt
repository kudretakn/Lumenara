package dev.lumenara.catalog

/** Number of retries already scheduled, excluding the initial request. */
fun liveRetryDelay(retries: Int, preview: Boolean = false): Long? =
    listOf(1000L, 2000L, 4000L, 8000L, 15000L)
        .take(if (preview) 2 else 5).getOrNull(retries)

fun channelNumber(tvgChno: String?, position: Int): Int =
    tvgChno?.toIntOrNull()?.takeIf { it > 0 } ?: (position + 1)

fun <T> resolveChannel(number: Int, channels: List<T>, chno: (T) -> String? = { null }): T? =
    channels.withIndex().firstOrNull { channelNumber(chno(it.value), it.index) == number }?.value

sealed interface NumberCommand {
    data object Previous : NumberCommand
    data class Select(val number: Int) : NumberCommand
}

data class NumberTransition(val state: NumberInput, val command: NumberCommand? = null)

/** All deadlines are supplied by the caller, so no platform clock or scheduler is needed. */
data class NumberInput(val digits: String = "", val deadline: Long = 0, val missingUntil: Long = 0) {
    fun digit(value: Int, now: Long): NumberTransition {
        require(value in 0..9)
        if (digits.isEmpty() && value == 0) return NumberTransition(NumberInput(), NumberCommand.Previous)
        return NumberTransition(copy(digits = (digits + value).take(4), deadline = now + 1500, missingUntil = 0))
    }
    fun confirm(): NumberTransition = if (digits.isEmpty()) NumberTransition(this)
        else NumberTransition(NumberInput(), NumberCommand.Select(digits.toInt()))
    fun tick(now: Long): NumberTransition = when {
        digits.isNotEmpty() && now >= deadline -> confirm()
        missingUntil != 0L && now >= missingUntil -> NumberTransition(NumberInput())
        else -> NumberTransition(this)
    }
    fun missing(now: Long) = NumberInput(missingUntil = now + 2000)
}
