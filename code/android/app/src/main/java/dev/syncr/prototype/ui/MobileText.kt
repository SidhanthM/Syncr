package dev.syncr.prototype.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.syncr.core.*
import dev.syncr.prototype.companion.SubscriptionClient
import dev.syncr.prototype.notes.*
import kotlinx.coroutines.*

private val draftSaver = listSaver<TextDraft, String>(
    save = { listOf(it.noteId, it.blockId.orEmpty(), it.head.orEmpty(), it.text) },
    restore = { TextDraft(it[0], it[1].ifEmpty { null }, it[2].ifEmpty { null }, it[3]) })

/** Small canvas controls; the editable text itself stays in NoteCanvasView. */
@Composable
internal fun MobileTextEditor(repo: NotesRepository, block: String?, canvas: NoteCanvasView?, dismiss: () -> Unit) {
    val draft = rememberSaveable(saver = draftSaver) { repo.textDraft(block) }
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.text)) }
    var error by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun close() { if (value.text != draft.text) discard = true else dismiss() }
    var passage by remember { mutableStateOf<TextDraft?>(null) }
    SideEffect { canvas?.setTextEditingEnabled(!busy && !asking) }
    androidx.activity.compose.BackHandler(enabled = !asking) { if (!busy) close() }
    DisposableEffect(canvas, draft.noteId, draft.blockId) {
        canvas?.beginTextEditing(draft, value.text,
            changed = { value = value.copy(text = it, selection = androidx.compose.ui.text.TextRange(value.selection.start.coerceAtMost(it.length), value.selection.end.coerceAtMost(it.length))) },
            selection = { start, end -> value = value.copy(selection = androidx.compose.ui.text.TextRange(start, end)) })
        onDispose { canvas?.endTextEditing() }
    }
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(enabled = !busy, onClick = ::close) { Text("Cancel") }
                TextButton(enabled = !busy && !value.selection.collapsed, onClick = {
                    busy = true; error = ""
                    scope.launch {
                        try {
                            repo.saveText(draft, value.text)
                            passage = repo.textDraft(draft.blockId)
                            asking = true
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not save. Your text is still here." }
                        finally { busy = false }
                    }
                }) { Text("Explain selection") }
                TextButton(enabled = !busy, onClick = {
                    busy = true; error = ""
                    scope.launch {
                        try { repo.saveText(draft, value.text); dismiss() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not save. Your text is still here." }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Saving…" else "Done") }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard unsaved edits?") },
        confirmButton = { TextButton(onClick = dismiss) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
    if (asking) passage?.let { saved ->
        ExplainPassage(repo, saved, value.selection.min, value.selection.max) { asking = false; dismiss() }
    }
}

@Composable
internal fun ResponseInspector(repo: NotesRepository, note: NoteSnapshot, id: String, dismiss: () -> Unit) {
    val item = note.companionItems.firstOrNull { it.id == id && !it.tombstone }
    val margin = item?.body as? CompanionBody.MarginNote
    val messages = note.companionItems.filter { !it.tombstone }.mapNotNull { it.body as? CompanionBody.ThreadMessage }
        .filter { it.marginId == id }.sortedBy { it.order }
    val text = if (messages.isNotEmpty()) messages.joinToString("\n\n") { it.text } else margin?.text.orEmpty()
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    // Capture the destination revision before interaction so sync cannot silently overwrite text.
    val destination = rememberSaveable(saver = draftSaver) { repo.textDraft() }
    fun run(action: suspend () -> Unit) {
        busy = true; error = ""
        scope.launch { try { action(); dismiss() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not save." } finally { busy = false } }
    }
    Dialog(onDismissRequest = { if (!busy) dismiss() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Response", style = MaterialTheme.typography.titleLarge)
                SelectionContainer { Text(text.ifEmpty { "This response is no longer available." }) }
                val sources = (margin?.sources.orEmpty() + messages.flatMap { it.sources }).distinctBy { it.url }
                sources.forEach { source -> TextButton(onClick = {
                    val uri = Uri.parse(source.url)
                    if (uri.scheme in listOf("https", "http")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }) { Text(source.url) } }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(enabled = text.isNotEmpty(), onClick = { copy(context, text) }) { Text("Copy") }
                    TextButton(enabled = !busy && text.isNotEmpty(), onClick = {
                        run { repo.saveText(destination, destination.text.trimEnd() + "\n\n" + text + "\n") }
                    }) { Text("Add to note") }
                }
                if (margin != null && item != null) Row {
                    TextButton(enabled = !busy, onClick = { run { repo.setResponseState(note.noteId, id, item.head.operationId,
                        if (margin.state == MarginState.PINNED) MarginState.OPEN else MarginState.PINNED) } }) {
                        Text(if (margin.state == MarginState.PINNED) "Unpin" else "Pin")
                    }
                    TextButton(enabled = !busy, onClick = { run { repo.setResponseState(note.noteId, id, item.head.operationId, MarginState.DISMISSED) } }) { Text("Dismiss response") }
                }
                TextButton(enabled = !busy, onClick = dismiss) { Text("Close") }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Syncr", text))
}

@Composable
private fun ExplainPassage(repo: NotesRepository, draft: TextDraft, start: Int, end: Int, dismiss: () -> Unit) {
    val context = LocalContext.current; val client = remember { SubscriptionClient.get(context) }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(emptyList<SubscriptionClient.Account>()) }
    var active by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(emptyList<SubscriptionClient.Model>()) }
    var model by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    suspend fun reload() { accounts = client.accounts(); active = client.active() }
    suspend fun loadModels() {
        models = client.models(); model = models.firstOrNull()?.id.orEmpty()
        check(models.isNotEmpty()) { "No eligible models were returned for this account." }
    }
    fun run(message: String, action: suspend () -> Unit) {
        busy = true; status = message
        job = scope.launch {
            try { action(); status = "" }
            catch (e: CancellationException) { status = "Cancelled. Nothing was saved."; throw e }
            catch (e: Exception) { status = e.message ?: "Could not complete the request." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { try { reload() } catch (_: Exception) { status = "Could not read secure sign-in storage." } }
    Dialog(onDismissRequest = { if (busy || answer.isNotEmpty()) confirmClose = true else dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Explain passage", style = MaterialTheme.typography.titleLarge)
                Text(draft.text.substring(start, end).take(1200), style = MaterialTheme.typography.bodyMedium)
                Text("Only the selected passage is sent to OpenAI when you tap Generate. Uses your ChatGPT plan allowance; no API-key billing or Mac connection.", style = MaterialTheme.typography.bodySmall)
                accounts.forEach { account ->
                    TextButton(enabled = !busy, onClick = { run("Selecting account…") {
                        client.select(account.client); reload(); models = emptyList(); model = ""
                        if (account.signedIn) loadModels() else { client.signIn(account.client); reload(); loadModels() }
                    } }) { Text((if (active == account.client) "✓ " else "") + account.label) }
                }
                Button(enabled = !busy, onClick = { run("Finish sign-in in your browser, then return here…") {
                    val existing = accounts.firstOrNull { it.client == active }?.client
                    client.signIn(existing); reload(); loadModels()
                } }) { Text("Continue with ChatGPT") }
                if (accounts.isNotEmpty()) TextButton(enabled = !busy, onClick = { run("Finish sign-in in your browser…") {
                    client.signIn(); reload(); models = emptyList(); model = ""; loadModels()
                } }) { Text("Add another account") }
                Row {
                    TextButton(enabled = !busy && active.isNotEmpty(), onClick = { run("Loading models…") { loadModels() } }) { Text("Refresh models") }
                    TextButton(enabled = !busy && active.isNotEmpty(), onClick = { run("Signing out…") {
                        val revoked = client.signOut(); models = emptyList(); model = ""; reload()
                        if (!revoked) error("Signed out locally. Remote revocation was not confirmed; disconnect Syncr in ChatGPT settings.")
                    } }) { Text("Sign out") }
                }
                Box {
                    OutlinedButton(enabled = !busy && models.isNotEmpty(), onClick = { menu = true }) {
                        Text(models.firstOrNull { it.id == model }?.name ?: "Choose a model")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        models.forEach { row -> DropdownMenuItem(text = { Text(row.name) }, onClick = { model = row.id; menu = false }) }
                    }
                }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }) { Text("ChatGPT usage & limits") }
                if (status.isNotEmpty()) Text(status)
                if (busy) TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
                Button(enabled = !busy && model.isNotEmpty() && answer.isEmpty(), onClick = { run("Generating explanation…") {
                    answer = client.explain(model, draft.text.substring(start, end))
                } }) { Text("Generate") }
                if (answer.isNotEmpty()) {
                    SelectionContainer { Text(answer) }
                    Row {
                        TextButton(onClick = { copy(context, answer) }) { Text("Copy") }
                        Button(enabled = !busy, onClick = { run("Saving response…") {
                            repo.saveExplanation(draft, start, end, answer); dismiss()
                        } }) { Text("Save to passage") }
                    }
                }
                TextButton(onClick = { if (busy || answer.isNotEmpty()) confirmClose = true else dismiss() }) { Text("Close") }
            }
        }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("Close explanation?") },
        text = { Text("Any active request will be cancelled and this unsaved response discarded. Usage already incurred may still count.") },
        confirmButton = { TextButton(onClick = { job?.cancel(); dismiss() }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep open") } })
}
