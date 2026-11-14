package dev.lumenara.catalog

import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class AudioSubtitlesTest {
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
