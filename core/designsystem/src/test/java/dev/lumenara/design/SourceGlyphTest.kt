package dev.lumenara.design

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceGlyphTest {
    @Test
    fun productionSourcesContainNoUnicodeIconGlyphs() {
        // Gradle may start tests from either the repository or the module directory.
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate repository sources; refusing to pass an empty scan")
        val coreModules = File(root, "core").listFiles().orEmpty().filter { it.isDirectory }
        val roots = listOf(File(root, "app/src/main")) + coreModules.map { File(it, "src/main") }
        val sources = roots.filter { it.isDirectory }.flatMap { directory ->
            directory.walkTopDown().filter { it.isFile && it.extension in SOURCE_EXTENSIONS }.toList()
        }
        assertTrue("The scan must include app and core production sources", sources.isNotEmpty())
        assertTrue("No design system sources found", sources.any {
            it.relativeTo(root).invariantSeparatorsPath.startsWith("core/designsystem/src/main/")
        })
        val violations = mutableListOf<String>()
        var count = 0
        sources.forEach { source ->
            source.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                val hits = line.count { it in FORBIDDEN_GLYPHS }
                if (hits > 0) {
                    count += hits
                    violations += "${source.relativeTo(root).invariantSeparatorsPath}:${index + 1} ($hits)"
                }
            }
        }
        assertEquals("Unicode icon glyphs found:\n${violations.joinToString("\n")}", 0, count)
    }

    private companion object {
        val FORBIDDEN_GLYPHS = setOf('\u2713', '\u2605', '\u25B6', '\u2022', '\u2190', '\u2192')
        val SOURCE_EXTENSIONS = setOf("kt", "java", "xml", "json", "html", "js", "css", "txt", "properties", "pro", "aidl", "c", "cpp", "h")
    }
}
