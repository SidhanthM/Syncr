package dev.syncr.prototype.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.syncr.core.*
import dev.syncr.prototype.ui.MobileTextEditor
import dev.syncr.prototype.ui.ResponseInspector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses an isolated library, never the installed app's notes or credentials. */
class MobileTextUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typeSaveReopenAndKeepUnsavedDraft() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "typing-test-${UUID.randomUUID()}")
        val repo = NotesRepository(root.absolutePath)
        try {
            runBlocking { repo.create("Typing check") }
            var open by mutableStateOf(true)
            compose.setContent {
                val context = androidx.compose.ui.platform.LocalContext.current
                val canvas = remember { NoteCanvasView(context) }
                val state by repo.state.collectAsState()
                MaterialTheme {
                    androidx.compose.foundation.layout.Box {
                        androidx.compose.ui.viewinterop.AndroidView(factory = { canvas },
                            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                            update = { it.update(state, false) })
                        if (open) MobileTextEditor(repo, null, canvas) { open = false }
                    }
                }
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Note text"))
                .perform(androidx.test.espresso.action.ViewActions.replaceText("Typed on the phone"))
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Keep editing").performClick()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Note text"))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.withText("Typed on the phone")))
            compose.onNodeWithText("Done", substring = false).performClick()
            compose.waitUntil(10_000) { !open }
            assertEquals("Typed on the phone", repo.state.value.snapshot!!.textBlocks.single().text)
            compose.runOnIdle { open = true }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Note text"))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.withText("Typed on the phone")))
                .perform(androidx.test.espresso.action.ViewActions.replaceText("Edited synced text"))
            compose.onNodeWithText("Done", substring = false).performClick()
            compose.waitUntil(10_000) { !open }
            assertEquals("Edited synced text", repo.state.value.snapshot!!.textBlocks.single().text)
        } finally { runBlocking { repo.leave(discard = true); repo.releaseLibrary() }; root.deleteRecursively() }
    }

    @Test fun responseActionsPersistWithoutAChat() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "response-test-${UUID.randomUUID()}")
        val repo = NotesRepository(root.absolutePath)
        try {
            runBlocking {
                repo.create("Response check"); repo.saveText(repo.textDraft(), "Explain this passage.")
                repo.saveExplanation(repo.textDraft(), 0, 20, "A contextual explanation.")
            }
            var open by mutableStateOf(true)
            val id = repo.state.value.snapshot!!.companionItems.first { it.body is CompanionBody.MarginNote }.id
            compose.setContent {
                val state by repo.state.collectAsState()
                MaterialTheme { if (open) ResponseInspector(repo, state.snapshot!!, id) { open = false } }
            }
            compose.onNodeWithText("A contextual explanation.").assertExists()
            compose.onNodeWithText("Copy").performClick()
            compose.onNodeWithText("Pin", substring = false).performClick()
            compose.waitUntil(10_000) { !open }
            assertEquals(MarginState.PINNED, (repo.state.value.snapshot!!.companionItems.first { it.id == id }.body as CompanionBody.MarginNote).state)
            compose.runOnIdle { open = true }
            compose.onNodeWithText("Add to note").performClick()
            compose.waitUntil(10_000) { !open }
            assertTrue(repo.state.value.snapshot!!.textBlocks.single().text.contains("A contextual explanation."))
            compose.runOnIdle { open = true }
            compose.onNodeWithText("Dismiss response").performClick()
            compose.waitUntil(10_000) { !open }
            assertEquals(MarginState.DISMISSED, (repo.state.value.snapshot!!.companionItems.first { it.id == id }.body as CompanionBody.MarginNote).state)
        } finally { runBlocking { repo.leave(discard = true); repo.releaseLibrary() }; root.deleteRecursively() }
    }
}
