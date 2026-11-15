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
