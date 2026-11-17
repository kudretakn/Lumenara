package dev.lumenara.data

import dev.lumenara.catalog.searchable

data class ProgramHit(val slot: Slot, val itemId: String)
data class AiringProgram(val itemId: String, val end: Long, val title: String)
data class GuideChannel(
    val id: String,
    val title: String,
    val tvgId: String,
    val search: String = searchable(title),
    val source: String = "",
    val category: String = "",
)
data class ProgrammeChannel(val feed: String, val channel: String, val source: String = "")
data class GuideTarget(val feed: String, val channel: String)

/** Resolves one source, or all sources without allowing automatic matches across sources. */
class GuideResolver(
    channels: List<GuideChannel>,
    mappings: List<Mapping>,
    names: List<GuideName>,
    present: Set<ProgrammeChannel>,
) {
    private val channelsById = channels.associateBy { it.id }
    private val itemsBySlot: Map<Pair<String, String>, List<String>>
    private val targetsByItem: Map<String, List<GuideTarget>>

    init {
        val explicit = mappings.associateBy { it.item }
        val feedsBySource = present.groupBy { it.source }.mapValues { (_, rows) -> rows.map { it.feed }.toSet() }
        val pairs = present.mapTo(HashSet()) { it.feed to it.channel }
        val uniqueNames = names.groupBy { it.feed to it.name }.mapValues { (_, rows) ->
            rows.map { it.channel }.distinct().singleOrNull()
        }
        val resolved = HashMap<Pair<String, String>, MutableList<String>>()
        for (item in channels) {
            val mapping = explicit[item.id]
            if (mapping != null) {
                // Even a stale explicit mapping suppresses all automatic matches.
                if (mapping.feed in feedsBySource[item.source].orEmpty())
                    resolved.getOrPut(mapping.feed to mapping.channel) { ArrayList() }.add(item.id)
                continue
            }
            // Most provider channels have an exact tvgId. Normalize only when a feed
            // actually needs the name fallback, and reuse it across that item's feeds.
            val name by lazy(LazyThreadSafetyMode.NONE) { searchable(item.title) }
            for (feed in feedsBySource[item.source].orEmpty()) {
                val channel = if (item.tvgId.isNotEmpty() && (feed to item.tvgId) in pairs) item.tvgId
                    else uniqueNames[feed to name] ?: continue
                resolved.getOrPut(feed to channel) { ArrayList() }.add(item.id)
            }
        }
        itemsBySlot = resolved
        val targets = HashMap<String, MutableList<GuideTarget>>()
        for ((pair, ids) in resolved) for (id in ids)
            targets.getOrPut(id) { ArrayList() }.add(GuideTarget(pair.first, pair.second))
        targetsByItem = targets
    }

    fun itemIds(slot: Slot): List<String> = itemsBySlot[slot.feed to slot.channel].orEmpty()
    fun targets(itemId: String): List<GuideTarget> = targetsByItem[itemId].orEmpty()
    fun visible(itemId: String, hidden: List<String>): Boolean =
        channelsById[itemId]?.let { it.category !in hidden } == true

    fun searchPrograms(slots: List<Slot>, query: String, from: Long, now: Long,
        hidden: List<String> = emptyList()): List<ProgramHit> {
        val normalized = searchable(query)
        fun rank(slot: Slot) = when {
            slot.start <= now && slot.end > now -> 0
            slot.start > now -> 1
            else -> 2
        }
        return slots.asSequence().filter { it.end > from && normalized in it.search }
            .flatMap { slot -> itemIds(slot).asSequence().filter { visible(it, hidden) }.map { ProgramHit(slot, it) } }
            .sortedWith(compareBy<ProgramHit> { rank(it.slot) }
                .thenBy { if (rank(it.slot) == 2) -it.slot.start else it.slot.start }
                .thenBy { it.itemId }.thenBy { it.slot.feed }.thenBy { it.slot.channel })
            .take(60).toList()
    }

    fun nowAiring(slots: List<Slot>, now: Long, query: String = ""): List<AiringProgram> {
        val normalized = searchable(query)
        val airing = HashMap<String, AiringProgram>()
        // Stable tie order also makes the title deterministic when end times match.
        for (slot in slots.sortedWith(compareBy<Slot> { it.end }.thenBy { it.feed }
            .thenBy { it.channel }.thenBy { it.start })) {
            if (slot.start > now || slot.end <= now) continue
            for (id in itemIds(slot)) {
                val item = channelsById.getValue(id)
                if (normalized.isNotEmpty() && normalized !in item.search && normalized !in searchable(item.title)) continue
                airing.putIfAbsent(id, AiringProgram(id, slot.end, slot.title))
            }
        }
        return airing.values.sortedWith(compareBy<AiringProgram> { it.end }.thenBy { it.itemId })
    }
}
