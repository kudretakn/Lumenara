package dev.lumenara.catalog

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

enum class AudioVersion(val label: String) { DUBBED("Dublaj"), SUBTITLED("Altyazılı"), ORIGINAL("Orijinal") }

data class ProductionName(val title: String, val year: String, val version: AudioVersion) {
    fun key(tmdb: String = ""): String = tmdb.trim().toLongOrNull()?.takeIf { it > 0 }
        ?.let { "tmdb:$it" } ?: "${searchable(title)}|$year"
}

private val dubbedTag = Regex("\\b(?:(?:tr|turkce)\\s+)?dublaj\\b")
private val subtitledTag = Regex("\\b(?:altyazili|(?:tr\\s+)?altyazi)\\b")
private val versionTags = Regex("(?iu)\\b(?:(?:tr|türkçe|turkce)\\s+)?dublaj\\b|\\b(?:altyazılı|altyazili|(?:tr\\s+)?altyazı|(?:tr\\s+)?altyazi)\\b")
private val productionYear = Regex("\\b(?:19|20)\\d{2}\\b")
private val emptyDelimiters = Regex("""\(\s*\)|\[\s*\]""")
private val whitespace = Regex("\\s+")
// Only explicit provider/language prefixes are identity noise; a subtitle after ':' is a title.
private val productionPrefix = Regex("""(?iu)^(?:\[\s*(?:TR|TUR|TÜRKÇE|TURKCE|VOD|IPTV)\s*]\s*|(?:TR|TUR|TÜRKÇE|TURKCE|VOD|IPTV)\s*[:|\-–]\s*)""")
private val qualityTags = Regex("""(?iu)(?<![\p{L}\p{N}])(?:4K|UHD|FHD|HD|SD|2160p|1080p|720p|480p)(?![\p{L}\p{N}])""")

fun productionName(title: String, year: String = ""): ProductionName {
    val folded = searchable(title)
    val version = when {
        dubbedTag.containsMatchIn(folded) -> AudioVersion.DUBBED
        subtitledTag.containsMatchIn(folded) -> AudioVersion.SUBTITLED
        else -> AudioVersion.ORIGINAL
    }
    val resolvedYear = productionYear.find(year)?.value ?: productionYear.find(title)?.value.orEmpty()
    var clean = qualityTags.replace(versionTags.replace(title, ""), "")
        .replace(emptyDelimiters, "").trim()
    while (true) {
        val prefix = productionPrefix.find(clean) ?: break
        clean = clean.removeRange(prefix.range).trim()
    }
    if (resolvedYear.isNotEmpty()) clean = productionYear.replace(clean) { if (it.value == resolvedYear) "" else it.value }
    clean = clean.replace(emptyDelimiters, "").replace(whitespace, " ")
        .trim(' ', '-', '·', '|', '/', ':', '.', '_', '(', ')', '[', ']')
    return ProductionName(clean.ifBlank { title }, resolvedYear, version)
}

enum class SubtitleType(val extension: String, val mime: String) {
    SRT("srt", "application/x-subrip"), VTT("vtt", "text/vtt"), ASS("ass", "text/x-ssa")
}
data class DecodedSubtitle(val text: String, val encoding: String, val type: SubtitleType)

fun decodeSubtitle(bytes: ByteArray, name: String): DecodedSubtitle {
    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    val encoding = if (utf8 != null) "UTF-8" else if (bytes.any { (it.toInt() and 255) in 0x80..0x9f })
        "windows-1254" else "ISO-8859-9"
    val text = (utf8 ?: String(bytes, Charset.forName(encoding))).removePrefix("\uFEFF")
    val type = when {
        text.trimStart().startsWith("WEBVTT") -> SubtitleType.VTT
        text.contains("[Script Info]", true) || text.contains("[Events]", true) -> SubtitleType.ASS
        name.substringAfterLast('.').lowercase(Locale.ROOT) in listOf("ass", "ssa") -> SubtitleType.ASS
        name.endsWith(".vtt", true) -> SubtitleType.VTT
        else -> SubtitleType.SRT
    }
    require(text.contains("-->") || type == SubtitleType.ASS && text.contains("Dialogue:", true)) {
        "Altyazı biçimi desteklenmiyor."
    }
    return DecodedSubtitle(text, encoding, type)
}

fun normalizedLanguage(code: String?): String = when (val language = code?.lowercase(Locale.ROOT)?.substringBefore('-')) {
    "tur", "turkish", "türkçe" -> "tr"
    "eng" -> "en"
    "deu", "ger" -> "de"
    "fra", "fre" -> "fr"
    else -> language.orEmpty()
}

fun languageName(code: String?): String = when (normalizedLanguage(code)) {
    "tr", "tur", "türkçe", "turkish" -> "Türkçe"
    "en", "eng" -> "İngilizce"
    "", "und" -> "Bilinmeyen dil"
    else -> Locale.forLanguageTag(code.orEmpty()).getDisplayLanguage(Locale.forLanguageTag("tr"))
        .replaceFirstChar { it.titlecase(Locale.forLanguageTag("tr")) }
}
fun isTurkish(code: String?) = normalizedLanguage(code) == "tr"

/** An explicit per-production choice wins over the automatic Turkish-audio policy. */
fun subtitlesDisabled(saved: String, turkishAudio: Boolean, dubbed: Boolean): Boolean = when (saved) {
    "off" -> true
    "" -> turkishAudio || dubbed
    else -> false
}

fun trackChoice(language: String?, id: String?): String =
    if (id != null) "id:$id|lang:${language.orEmpty()}" else language.orEmpty()

/** Exact track first; fall back to its language when a provider or engine changes track IDs. */
fun trackPreferenceRank(saved: String, language: String?, id: String?): Int {
    if (saved.isBlank()) return 2
    if (saved.startsWith("id:")) {
        val savedId = saved.removePrefix("id:").substringBefore("|lang:")
        if (id != null && savedId == id) return 0
        val savedLanguage = saved.substringAfter("|lang:", "")
        return if (savedLanguage.isNotBlank() && normalizedLanguage(savedLanguage) == normalizedLanguage(language)) 1 else 2
    }
    return if (normalizedLanguage(saved) == normalizedLanguage(language)) 1 else 2
}
