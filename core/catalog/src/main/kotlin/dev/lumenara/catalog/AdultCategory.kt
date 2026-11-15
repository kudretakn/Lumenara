package dev.lumenara.catalog

private val adultCategory = Regex(
    """(?<![\p{L}\p{N}])(?:adults?|xxx|yetiskin|erotik|porn|sex)(?![\p{L}\p{N}])|(?<!\d)(?:18\+|\+18|\(18\))(?!\d)""",
)

fun isAdultCategory(name: String): Boolean = adultCategory.containsMatchIn(searchable(name))
