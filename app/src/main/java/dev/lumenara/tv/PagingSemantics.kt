package dev.lumenara.tv

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

/** Test-only observable metadata; no catalog rows or artwork are retained here. */
internal val PagingWindowKey = SemanticsPropertyKey<String>("PagingWindow")
internal var SemanticsPropertyReceiver.pagingWindow by PagingWindowKey

internal fun pagingDescription(pages: WindowPages): String =
    "${pages.pages.keys.minOrNull() ?: -1}:${pages.pages.keys.maxOrNull() ?: -1}:" +
        "${pages.pages.size}:${pages.pages.values.sumOf { it.size }}"
