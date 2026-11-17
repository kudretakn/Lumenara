package dev.lumenara.data

internal fun Item.carryRow() = CarryRow(id, source, title, category, kind, tvgId, year, parent,
    providerId, tvgName, tvgGroup, versionKey = versionKey)

/** All inserts, merges, aliases and removals execute inside the caller's Room transaction. */
internal suspend fun carryRoomState(db: LibraryDb, ids: Map<String, String>, additionalIds: Set<String> = emptySet()) {
    val dao = db.library()
    if (ids.isEmpty()) return
    val marks = dao.backupMarks().associateBy { it.id }
    val members = dao.carryMembers().associateBy { it.id }
    val mappings = db.guide().backupMappings().associateBy { it.item }
    val redirectTargets = dao.redirects().map { it.newId }.toSet()
    val referenced = marks.keys + members.keys + mappings.keys + redirectTargets + additionalIds
    for ((from, to) in ids) {
        marks[from]?.let { old ->
            val current = marks[to]
            val newest = if (current != null && current.updated >= old.updated) current else old
            dao.save(newest.copy(id = to, favorite = old.favorite || current?.favorite == true,
                listed = old.listed || current?.listed == true))
            dao.deleteMark(from)
        }
        members[from]?.let { if (members[to] == null) dao.collection(it.copy(id = to)); dao.deleteMember(from) }
        mappings[from]?.let { if (mappings[to] == null) db.guide().map(it.copy(item = to)); dao.deleteMapping(from) }
        if (from in redirectTargets) dao.redirectTarget(from, to)
    }
    ids.entries.filter { it.key in referenced }.chunked(256)
        .forEach { batch -> dao.saveRedirects(batch.map { ItemRedirect(it.key, it.value) }) }
    dao.deleteSelfRedirects()
}

