package dev.lumenara.tv

import java.util.Locale

private val channelCountryPrefix = Regex("^([A-Z]{2}):\\s*")
private val categoryCountryPrefix = Regex("^([A-Z]{2})(?=$|[:\\s➾\u2192|–-])")

/** Only remove redundant country context supplied by the open category. */
internal fun channelCardName(title: String, categoryName: String?): String {
    val prefix = channelCountryPrefix.find(title) ?: return title
    val categoryPrefix = categoryName?.let { categoryCountryPrefix.find(it) } ?: return title
    if (prefix.groupValues[1] != categoryPrefix.groupValues[1]) return title
    return title.substring(prefix.range.last + 1).trim().ifBlank { title }
}

internal fun channelCardInitials(title: String): String {
    val name = title.replaceFirst(channelCountryPrefix, "")
    val words = Regex("[\\p{L}\\p{N}]+").findAll(name).map { it.value }.toList()
    val initials = if (words.size > 1) words.take(3).joinToString("") { it.take(1) }
        else words.firstOrNull().orEmpty().take(3)
    return initials.uppercase(Locale.ROOT).take(3).ifBlank { "TV" }
}
