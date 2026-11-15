package dev.lumenara.catalog

private val providerPrefix = Regex(
    """(?iu)^(?:(?:\[[^\]]+]\s*)|(?:[^|:]{1,40}\s*\|\s*)|(?:(?:SAĞLAYICI|PROVIDER|VOD|IPTV|TR)\s*:\s*)|(?:(?:TR|TUR|TÜRKÇE|VOD|IPTV|UHD|FHD|HD|4K)\s*[-–]\s*))""",
)
private val providerId = Regex("""(?<!\d)\d{6,}(?!\d)""")
private val emptyTitleBrackets = Regex("""\(\s*\)|\[\s*]""")

/** Presentation only: keep provider titles and production identities intact in storage. */
fun String.displayTitle(): String {
    var clean = trim()
    while (true) {
        val prefix = providerPrefix.find(clean) ?: break
        clean = clean.removeRange(prefix.range).trim()
    }
    return clean.replace(providerId, "")
        .replace(emptyTitleBrackets, "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '–', '·', '|', ':', '_')
}
