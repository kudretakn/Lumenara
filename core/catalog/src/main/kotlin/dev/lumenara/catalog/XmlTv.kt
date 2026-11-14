package dev.lumenara.catalog

import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class Programme(
    val channel: String,
    val start: Long,
    val end: Long,
    val title: String,
    val description: String,
)

object XmlTv {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun time(value: String): Long? = runCatching {
        val parts = value.trim().split(Regex("\\s+"))
        val offset = ZoneOffset.of(parts.getOrElse(1) { "Z" })
        LocalDateTime.parse(parts[0], stamp).toInstant(offset).toEpochMilli()
    }
        .getOrNull()

    fun read(input: InputStream, channel: (String, String) -> Unit, emit: (Programme) -> Unit) {
        val parser = SAXParserFactory.newInstance().newSAXParser().xmlReader
        // External entities never participate in guide processing.
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        parser.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource("".reader()) }
        parser.contentHandler =
            object : DefaultHandler() {
                var id = ""
                var programme: Programme? = null
                var field = ""
                val buffer = StringBuilder()

                override fun startElement(
                    uri: String?,
                    local: String?,
                    name: String,
                    attrs: Attributes,
                ) {
                    when (name) {
                        "channel" -> id = attrs.getValue("id").orEmpty()
                        "programme" -> {
                            val start = time(attrs.getValue("start").orEmpty())
                            val end = time(attrs.getValue("stop").orEmpty())
                            programme =
                                if (start != null && end != null && end > start)
                                    Programme(
                                        attrs.getValue("channel").orEmpty(),
                                        start,
                                        end,
                                        "",
                                        "",
                                    )
                                else null
                        }
                        "display-name",
                        "title",
                        "desc" -> {
                            field = name
                            buffer.setLength(0)
                        }
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (field.isNotEmpty() && buffer.length < 8192)
                        buffer.append(ch, start, minOf(length, 8192 - buffer.length))
                }

                override fun endElement(uri: String?, local: String?, name: String) {
                    if (field == name) {
                        val value = buffer.toString().trim()
                        when (field) {
                            "display-name" ->
                                if (id.isNotEmpty() && value.isNotEmpty()) channel(id, value)
                            "title" ->
                                if (programme?.title.isNullOrEmpty())
                                    programme = programme?.copy(title = value)
                            "desc" ->
                                if (programme?.description.isNullOrEmpty())
                                    programme = programme?.copy(description = value)
                        }
                        field = ""
                    }
                    if (name == "channel") id = ""
                    if (name == "programme") {
                        programme
                            ?.takeIf { it.channel.isNotEmpty() && it.title.isNotEmpty() }
                            ?.let(emit)
                        programme = null
                    }
                }
            }
        parser.parse(InputSource(input))
    }
}
