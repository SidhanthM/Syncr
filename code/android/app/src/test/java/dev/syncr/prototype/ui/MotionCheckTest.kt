package dev.syncr.prototype.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mobile polish C.4: every animation takes its timing from `Motion`; nothing else in ui/, tablet/ or editor/ builds one. */
class MotionCheckTest {
    private val roots = listOf("ui", "tablet", "editor")
    private fun sources(): List<File> {
        val base = listOf("src/main/java/dev/syncr/prototype", "app/src/main/java/dev/syncr/prototype").map(::File).first { it.isDirectory }
        return roots.flatMap { File(base, it).walkTopDown().filter { f -> f.extension == "kt" && f.name != "Motion.kt" }.toList() }
    }
    @Test fun noLiteralAnimationSpecsOutsideMotion() {
        val bad = sources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) null
                else if (Regex("""\b(tween|spring)\(|durationMillis""").containsMatchIn(code)) "${f.name}:${i + 1}: $line" else null
            }
        }
        assertTrue("Animation specs outside Motion.kt:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
    @Test fun theSourcesWereFound() { assertTrue(sources().size > 20) }
}
