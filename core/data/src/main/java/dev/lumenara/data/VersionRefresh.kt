package dev.lumenara.data

/** Partial updates preserve URLs, provider metadata and all user records. */
data class TmdbAssignment(val id: String, val search: String, val year: String)
