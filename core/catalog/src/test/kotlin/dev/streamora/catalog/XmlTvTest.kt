package dev.streamora.catalog

import org.junit.Assert.*
import org.junit.Test

class XmlTvTest {
    @Test
    fun storesOffsetsAsUtc() {
        assertEquals(XmlTv.time("20260101100000 +0000"), XmlTv.time("20260101130000 +0300"))
        assertNull(XmlTv.time("broken"))
    }

    @Test
    fun skipsInvalidIntervalAndPreservesTurkishTitle() {
        val guide =
            """<tv><channel id="seven"><display-name>Işık</display-name></channel>
          <programme channel="seven" start="20260101130000 +0300" stop="20260101143000 +0300">
          <title>Öğle</title><desc>Haber &amp; spor</desc></programme>
          <programme channel="seven" start="bad"><title>Hatalı</title></programme></tv>"""
        val channels = mutableMapOf<String, String>()
        val rows =
            buildList<Programme> {
                XmlTv.read(guide.byteInputStream(), { k, v -> channels[k] = v }, ::add)
            }
        assertEquals("Işık", channels["seven"])
        assertEquals("Öğle", rows.single().title)
        assertEquals("Haber & spor", rows.single().description)
    }
}
