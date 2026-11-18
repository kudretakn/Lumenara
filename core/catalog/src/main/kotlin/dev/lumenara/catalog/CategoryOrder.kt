package dev.lumenara.catalog

import java.text.Collator
import java.util.Locale

private val turkishCategories = ThreadLocal.withInitial {
    Collator.getInstance(Locale.forLanguageTag("tr"))
}
private val trPrefix = Regex("""^tr(?!\p{L})""")

private fun isTrCategory(name: String): Boolean =
    trPrefix.containsMatchIn(searchable(name).dropWhile { !it.isLetterOrDigit() })

/** Adult groups last, then TR first, with Turkish alphabetical order within each group. */
val categoryOrder: Comparator<String> = Comparator { a, b ->
    val adult = isAdultCategory(a).compareTo(isAdultCategory(b))
    val tr = isTrCategory(b).compareTo(isTrCategory(a))
    when {
        adult != 0 -> adult
        tr != 0 -> tr
        else -> turkishCategories.get().compare(a, b)
    }
}

/** Compute sort keys once per category, instead of normalizing inside comparisons. */
fun <T> Iterable<T>.sortedByCategory(name: (T) -> String): List<T> {
    val collator = turkishCategories.get()
    data class Ranked<T>(val row: T, val adult: Boolean, val tr: Boolean,
        val key: java.text.CollationKey)
    return map { row ->
        val title = name(row)
        Ranked(row, isAdultCategory(title), isTrCategory(title), collator.getCollationKey(title))
    }.sortedWith(compareBy<Ranked<T>> { it.adult }.thenByDescending { it.tr }.thenBy { it.key })
        .map { it.row }
}

/** Persisted binary key: adult last, TR first, then Turkish collation. */
fun categorySortKey(name: String): String {
    val prefix = (if (isAdultCategory(name)) "1" else "0") + (if (isTrCategory(name)) "0" else "1")
    return prefix + turkishCategories.get().getCollationKey(name).toByteArray()
        .joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }
}
