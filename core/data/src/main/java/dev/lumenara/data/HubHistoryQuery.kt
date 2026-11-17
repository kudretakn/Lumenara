package dev.lumenara.data

// Keep intermediate rows narrow. GROUP BY prevents SQLite 3.22 (Android 9) from
// flattening candidates and repeating the remaining-episode probe for every version.
// There is no marks.updated index in this schema: sorting/grouping marks is necessary;
// every items access is instead a primary-key or parent-index lookup. Fetch full Item
// payloads only after choosing and limiting the shelf's IDs.
const val CONTINUE_WATCHING_SQL = """WITH history AS (
    SELECT CASE WHEN ep.kind='EPISODE' THEN ep.parent ELSE ep.id END AS cardId, MAX(m.updated) AS activity
    FROM marks m CROSS JOIN items ep ON ep.id=m.id WHERE ep.category NOT IN (:hidden) AND m.position>0 AND
        (ep.kind='EPISODE' OR ep.kind='MOVIE' AND (m.duration<=0 OR m.position<m.duration*0.95))
    GROUP BY cardId
    ), candidates AS (
    SELECT i.id,CASE WHEN i.versionKey='' THEN i.id ELSE i.versionKey END AS cardKey,
        i.versionRank,i.isRepresentative,i.versionKey!='' AS hasVersion,MAX(h.activity) AS activity
    FROM history h CROSS JOIN items i ON i.id=h.cardId
    WHERE i.category NOT IN (:hidden) AND (:source='' OR i.source=:source) AND i.kind IN ('MOVIE','SERIES')
    AND (i.kind!='SERIES' OR EXISTS(SELECT 1 FROM items ep INDEXED BY index_items_parent
        LEFT JOIN marks m ON m.id=ep.id WHERE ep.parent=i.id AND ep.category NOT IN (:hidden)
        AND (m.id IS NULL OR m.duration<=0 OR m.position<m.duration*0.90)))
    GROUP BY i.id
    ), activity AS (
    SELECT cardKey,MAX(activity) AS lastActivity,MIN(versionRank) AS bestRank
    FROM candidates GROUP BY cardKey
    ), selected AS (
    SELECT c.id,a.lastActivity FROM activity a CROSS JOIN candidates c ON c.cardKey=a.cardKey
    WHERE c.isRepresentative=1 OR (c.hasVersion AND c.versionRank=a.bestRank)
    ORDER BY a.lastActivity DESC,c.id LIMIT 24
    ) SELECT items.* FROM selected CROSS JOIN items ON items.id=selected.id
    ORDER BY selected.lastActivity DESC,items.id"""
