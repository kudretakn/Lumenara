package dev.lumenara.catalog

import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class AudioSubtitlesTest {
    @Test fun brokenProviderBracketsNeverLeakIntoTheTitle() {
        for (suffix in listOf("(2017", "[2017", "2017)", "2017]", "(2017]", "[4K", "4K)", "(2017) [4K")) {
            val name = productionName("Bıçak Sırtı $suffix")
            assertEquals(suffix, "Bıçak Sırtı", name.title)
            if (suffix.contains("2017")) assertEquals(suffix, "2017", name.year)
        }
        assertEquals("Film (Yönetmen Kurgusu)", productionName("Film (Yönetmen Kurgusu) 4K").title)
        assertEquals("Film Yönetmen Kurgusu", productionName("Film (Yönetmen Kurgusu [4K").title)
        assertEquals("1917", productionName("1917").title)
        assertEquals("", productionName("1917").year)
        assertEquals("2001: Bir Uzay Destanı", productionName("2001: Bir Uzay Destanı").title)
        assertEquals("Bıçak Sırtı 2049", productionName("Bıçak Sırtı 2049 (2017)").title)
        assertEquals("2017", productionName("Bıçak Sırtı 2049 (2017)").year)
        assertEquals("Bıçak Sırtı 2049", productionName("Bıçak Sırtı 2049 (2017").title)
        assertEquals("Bıçak Sırtı 2049", productionName("Bıçak Sırtı 2049").title)
        assertEquals("", productionName("[4K").title)
    }

    @Test fun turkishProviderPrefixesAndQualityTagsDoNotChangeTheTitle() {
        val titles = listOf("TR: Avatar (2009)", "Avatar 4K", "Avatar [Altyazılı]",
            "[TR] Avatar [2160p]", "TR | Avatar - FHD Türkçe Dublaj", "[4K] TR: Avatar")
        assertEquals(listOf("Avatar"), titles.map { productionName(it).title }.distinct())
        assertEquals(1, titles.map { productionName(it, "2009").key() }.distinct().size)
        assertEquals(AudioVersion.SUBTITLED, productionName(titles[2]).version)
        assertEquals("Avatar: Suyun Yolu", productionName("TR: Avatar: Suyun Yolu 4K").title)
        assertNotEquals(productionName(titles[0]).key(), productionName("Avatar: Suyun Yolu").key())
        assertEquals("HDeniz", productionName("HDeniz").title)
    }

    @Test fun providerVersionsHaveOneProductionIdentity() {
        val titles = listOf("Kıyı (Dublaj)", "Kıyı [TR Dublaj]", "Kıyı - Türkçe Dublaj", "Kıyı (Altyazılı)", "Kıyı TR Altyazı")
        assertEquals(1, titles.map { productionName(it, "2024").key() }.distinct().size)
        assertEquals(listOf(AudioVersion.DUBBED, AudioVersion.DUBBED, AudioVersion.DUBBED, AudioVersion.SUBTITLED, AudioVersion.SUBTITLED),
            titles.map { productionName(it).version })
        assertEquals("Kıyı", productionName("Kıyı (2024) [TR Dublaj]").title)
        assertEquals("2024", productionName("Kıyı (2024) [TR Dublaj]").year)
    }
    @Test fun remakesAndUnrelatedWordsStaySeparateButTmdbIsAuthoritative() {
        assertNotEquals(productionName("Kıyı", "1999").key(), productionName("Kıyı", "2024").key())
        assertEquals("Dublajcı", productionName("Dublajcı").title)
        assertEquals(productionName("Kıyı", "2024").key("0042"), productionName("The Coast").key("42"))
        assertEquals(productionName("Kıyı").key(), productionName("Kıyı").key("null"))
    }
    @Test fun turkishTextRoundTripsAcrossAllThreeEncodings() {
        val cue = "1\n00:00:01,000 --> 00:00:04,000\nÇığ, şüphe, ıslak öğle.\n"
        for (encoding in listOf("UTF-8", "windows-1254", "ISO-8859-9")) {
            val decoded = decodeSubtitle(cue.toByteArray(Charset.forName(encoding)), "test.srt")
            assertEquals(cue, decoded.text)
            assertEquals(SubtitleType.SRT, decoded.type)
        }
        val windows = cue + "“Türkçe”"
        assertEquals("windows-1254", decodeSubtitle(windows.toByteArray(Charset.forName("windows-1254")), "test.srt").encoding)
        assertEquals(windows, decodeSubtitle(windows.toByteArray(Charset.forName("windows-1254")), "test.srt").text)
    }
    @Test fun formatsAreDetectedFromContentAndBomIsRemoved() {
        val vtt = decodeSubtitle("\uFEFFWEBVTT\n\n00:01.000 --> 00:02.000\nMerhaba".toByteArray(), "download")
        assertEquals(SubtitleType.VTT, vtt.type)
        assertFalse(vtt.text.startsWith("\uFEFF"))
        assertEquals(SubtitleType.ASS, decodeSubtitle("[Script Info]\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Merhaba".toByteArray(), "download").type)
    }
    @Test fun exactTrackIsRememberedAndLanguageSurvivesAnEngineChange() {
        val saved = trackChoice("en", "commentary")
        assertEquals(0, trackPreferenceRank(saved, "en", "commentary"))
        assertEquals(1, trackPreferenceRank(saved, "en", "main"))
        assertEquals(2, trackPreferenceRank(saved, "tr", "turkish"))
        assertEquals(1, trackPreferenceRank("tr", "tr", "2"))
        assertEquals(1, trackPreferenceRank("en", "eng", "2"))
        assertTrue(isTurkish("tr-TR"))
    }
    @Test fun turkishAudioDefaultsToNoSubtitlesAndExplicitChoicesWin() {
        assertTrue(subtitlesDisabled("", turkishAudio = true, dubbed = false))
        assertTrue(subtitlesDisabled("", turkishAudio = false, dubbed = true))
        assertFalse(subtitlesDisabled("", turkishAudio = false, dubbed = false))
        assertFalse(subtitlesDisabled("tr", turkishAudio = true, dubbed = true))
        assertTrue(subtitlesDisabled("off", turkishAudio = false, dubbed = false))
    }
    @Test(expected = IllegalArgumentException::class) fun htmlIsNotASubtitle() {
        decodeSubtitle("<html>login</html>".toByteArray(), "test.srt")
    }
}
