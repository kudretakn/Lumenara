package dev.lumenara.data

import java.io.Reader
import org.json.JSONObject
import org.json.JSONTokener

/** Structural streaming reader shared by Android and JVM tests. Only one episode object is
 * materialized. Unknown provider metadata is consumed without retaining its object/array tree.
 * The transport's existing byte limit and cancellation apply to every underlying read.
 */
internal class Wp22EpisodeJson(private val input: Reader) {
    private var look = input.read()
    private fun take(): Int = look.also { look = input.read() }
    private fun space() { while (look >= 0 && look.toChar() in " \r\n\t") take() }
    private fun expect(char: Char) { space(); checkJson(look == char.code); take() }
    private fun checkJson(valid: Boolean) {
        if (!valid) throw SourceFailure("Sağlayıcı bölüm listesini eksik veya geçersiz döndürdü.")
    }
    private fun quoted(out: StringBuilder?) {
        checkJson(look == '"'.code)
        out?.append(take().toChar()) ?: take()
        while (true) {
            checkJson(look >= 0 && look >= 0x20)
            val char = take().toChar()
            out?.append(char)
            if (char == '"') return
            if (char == '\\') {
                checkJson(look >= 0)
                val escaped = take().toChar()
                out?.append(escaped)
                checkJson(escaped in "\"\\/bfnrtu")
                if (escaped == 'u') repeat(4) {
                    checkJson(look >= 0 && look.toChar() in "0123456789abcdefABCDEF")
                    out?.append(take().toChar()) ?: take()
                }
            }
        }
    }
    private fun key(): String {
        space()
        val text = StringBuilder()
        quoted(text)
        return JSONTokener(text.toString()).nextValue() as String
    }
    private fun value(out: StringBuilder? = null, depth: Int = 0) {
        checkJson(depth < 128)
        space()
        when (look) {
            '"'.code -> quoted(out)
            '{'.code, '['.code -> {
                val objectValue = look == '{'.code
                val close = if (objectValue) '}' else ']'
                out?.append(take().toChar()) ?: take()
                space()
                if (look != close.code) while (true) {
                    if (objectValue) {
                        space(); quoted(out); expect(':'); out?.append(':')
                    }
                    value(out, depth + 1)
                    space()
                    if (look != ','.code) break
                    take(); out?.append(',')
                }
                expect(close); out?.append(close)
            }
            else -> {
                val token = StringBuilder()
                while (look >= 0 && look.toChar() !in ",]} \r\n\t") {
                    checkJson(token.length < 128)
                    token.append(take().toChar())
                }
                val text = token.toString()
                checkJson(text in listOf("null", "true", "false") || NUMBER.matches(text))
                out?.append(text)
            }
        }
    }
    fun read(emit: (String, Int, JSONObject) -> Unit) {
        var found = false
        expect('{'); space()
        if (look != '}'.code) while (true) {
            val name = key(); expect(':'); space()
            if (name == "episodes") {
                checkJson(!found)
                found = true
                if (look == '['.code) { expect('['); expect(']') }
                else {
                    expect('{'); space()
                    if (look != '}'.code) while (true) {
                        val season = key(); expect(':'); expect('['); space()
                        var index = 0
                        if (look != ']'.code) while (true) {
                            space()
                            if (look == '{'.code) {
                                val raw = StringBuilder()
                                value(raw)
                                emit(season, index, JSONObject(raw.toString()))
                            } else value()
                            index++
                            space()
                            if (look != ','.code) break
                            take()
                        }
                        expect(']'); space()
                        if (look != ','.code) break
                        take()
                    }
                    expect('}')
                }
            } else value()
            space()
            if (look != ','.code) break
            take()
        }
        expect('}'); space()
        checkJson(found && look == -1)
    }
    private companion object {
        val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
