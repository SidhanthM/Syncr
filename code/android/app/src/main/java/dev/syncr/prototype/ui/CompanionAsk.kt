package dev.syncr.prototype.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.syncr.prototype.companion.PhoneCompanion
import dev.syncr.prototype.companion.SubscriptionClient
import dev.syncr.prototype.notes.NotesRepository
import dev.syncr.prototype.notes.TextDraft
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The ChatGPT account the companion on this phone uses, and its model. Sign-in happens in the browser and comes back
 * here; tokens stay in the phone's keystore-encrypted store and are never shown. [onModel] hears the usable model, or
 * null while there is none. Used in Settings and inside the ask sheet.
 */
@Composable
internal fun CompanionAccount(onModel: (SubscriptionClient.Model?) -> Unit = {}) {
    val c = Syncr.colors
    val context = LocalContext.current
    val client = remember { SubscriptionClient.get(context) }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(emptyList<SubscriptionClient.Account>()) }
    var active by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(emptyList<SubscriptionClient.Model>()) }
    var model by remember { mutableStateOf(client.model) }
    var choosing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val signedIn = accounts.firstOrNull { it.client == active }?.signedIn == true

    fun choose(next: SubscriptionClient.Model?) { model = next; client.model = next; onModel(next.takeIf { signedIn || accounts.any { it.signedIn } }) }
    suspend fun reload() { accounts = client.accounts(); active = client.active() }
    suspend fun loadModels() {
        models = client.models()
        check(models.isNotEmpty()) { "This account has no models it can use here." }
        // Keep the remembered model while the account still offers it; else the first the account lists.
        choose(models.firstOrNull { it.id == model?.id } ?: models.first())
    }
    fun run(message: String, action: suspend () -> Unit) {
        busy = true; status = message
        job = scope.launch {
            try { action(); status = "" }
            catch (e: CancellationException) { status = ""; throw e }
            catch (e: Exception) { status = e.message ?: "That did not work. Try again." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try {
            reload()
            val ready = accounts.firstOrNull { it.client == active }?.signedIn == true
            onModel(model.takeIf { ready })
        } catch (_: Exception) { status = "The saved sign-in could not be read. Sign in again." }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (!signedIn) {
            Text("Answers come from your ChatGPT plan's allowance. No API key is used and the Mac does not need to be on.",
                style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.m)) {
                PrimaryAction(if (busy) "Waiting for the browser…" else "Continue with ChatGPT", enabled = !busy) {
                    run("Finish signing in in the browser, then come back here.") {
                        client.signIn(accounts.firstOrNull { it.client == active }?.client)
                        reload(); loadModels()
                    }
                }
                if (busy) TextAction("Cancel", { job?.cancel(); busy = false; status = "" })
            }
        } else {
            val account = accounts.first { it.client == active }
            MenuRow(account.label.substringBefore(" · "), "Signed in with ChatGPT", enabled = false) {}
            MenuRow("Model", model?.name ?: "Not chosen yet", enabled = !busy) {
                if (models.isEmpty()) run("Loading models…") { loadModels(); choosing = true } else choosing = !choosing
            }
            if (choosing) models.forEach { m -> SettingsOption(m.name, selected = m.id == model?.id) { choose(m); choosing = false } }
            Row {
                TextAction("Usage and limits", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) } })
                TextAction("Sign out", {
                    run("Signing out…") {
                        val revoked = client.signOut()
                        models = emptyList(); choosing = false; reload(); onModel(null)
                        if (!revoked) error("Signed out on this phone. ChatGPT did not confirm it; remove Syncr under Settings in ChatGPT to be sure.")
                    }
                }, enabled = !busy, tone = ActionTone.DESTRUCTIVE)
            }
            accounts.filter { it.client != active }.forEach { other ->
                MenuRow(other.label.substringBefore(" · "), if (other.signedIn) "Switch to this account" else "Signed out", enabled = !busy) {
                    run("Switching account…") {
                        client.select(other.client); reload(); models = emptyList()
                        if (!other.signedIn) client.signIn(other.client)
                        reload(); loadModels()
                    }
                }
            }
        }
        if (status.isNotEmpty()) Text(status, style = Syncr.type.caption, color = if (busy) c.muted else c.attention)
    }
}

/**
 * Ask about a passage of the open note, on the phone's own ChatGPT sign-in. The answer streams in, and once it is
 * complete it is saved under the passage, where the note shows it on the phone and on the Mac. Nothing is sent until
 * Ask is tapped, nothing partial is saved, and nothing is retried.
 */
@Composable
internal fun AskCompanion(repo: NotesRepository, title: String, draft: TextDraft, selectionStart: Int, selectionEnd: Int,
    initialPicture: Boolean = false, dismiss: () -> Unit, saved: () -> Unit) {
    val c = Syncr.colors
    val context = LocalContext.current
    val client = remember { SubscriptionClient.get(context) }
    val scope = rememberCoroutineScope()
    val passage = remember(draft) { PhoneCompanion.passage(draft.text, selectionStart, selectionEnd) }
    var question by rememberSaveable { mutableStateOf("") }
    var picture by remember { mutableStateOf(initialPicture) }
    var model by remember { mutableStateOf<SubscriptionClient.Model?>(null) }
    var account by remember { mutableStateOf(false) }
    var streamed by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<PhoneCompanion.Answer?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val reduced = Syncr.reducedMotion
    val presence = remember { Presence(reduced) }
    val recorded = StippleDebug.stream && debugHooksAllowed(LocalContext.current) // debug builds: answer from a recorded stream, no sign-in and no network
    val canAsk = !busy && (model != null || recorded) && (passage != null || question.isNotBlank())

    fun ask() {
        val using = model
        if (using == null && !recorded) return
        busy = true; status = ""
        job = scope.launch {
            try {
                // An answer already shown dissolves out (two thirds of the time it took to arrive) before the next one comes.
                if (streamed.isNotEmpty() || answer != null) presence.leave()
                streamed = ""; answer = null
                val raw = if (using == null) {
                    var shown = 0
                    kotlinx.coroutines.delay(2500) // the waiting band, as before a model's first words
                    while (shown < StippleDebug.RECORDED.length) { shown = minOf(StippleDebug.RECORDED.length, shown + 6); streamed = StippleDebug.RECORDED.substring(0, shown); kotlinx.coroutines.delay(35) }
                    StippleDebug.RECORDED
                } else client.ask(using.id, PhoneCompanion.request(title, draft.text, passage, question, picture)) { streamed = it }
                val parsed = PhoneCompanion.parse(raw)
                answer = parsed
                status = "Saving…"
                val kept = repo.saveAsk(draft, passage, question, parsed)
                if (kept) saved() else status = "Saved the answer. The picture did not pass Syncr's checks and was left out."
            } catch (e: CancellationException) { status = "Stopped. Nothing was saved."; throw e }
            catch (e: Exception) { status = e.message ?: "The request did not complete. Nothing was saved." }
            finally { busy = false }
        }
    }

    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.surface2).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = Space.xl).padding(top = Space.s, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l)) {
            Row(Modifier.fillMaxWidth().heightIn(min = TouchTarget), verticalAlignment = Alignment.CenterVertically) {
                Text("Ask Syncr", style = Syncr.type.subheading, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                TextAction(if (busy) "Stop" else "Close", { if (busy) job?.cancel() else dismiss() })
            }
            if (passage != null) Row {
                Box(Modifier.width(2.dp).heightIn(min = 24.dp).background(c.accent))
                Text(draft.text.substring(passage.start, passage.end), style = Syncr.type.body, color = c.muted,
                    maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Space.m))
            }
            SyncrTextField(question, { if (it.length <= PhoneCompanion.MAX_QUESTION) question = it }, Modifier.fillMaxWidth(),
                enabled = !busy, minLines = 2,
                placeholder = if (passage != null) "Ask about this, or leave empty to have it explained" else "Ask a question; it becomes the note's next line")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip("Draw a picture", selected = picture, enabled = !busy,
                    description = if (picture) "A picture is asked for with the answer. Turn off" else "Ask for a picture with the answer") { picture = !picture }
                Spacer(Modifier.weight(1f))
                PrimaryAction(if (busy) "Asking…" else if (answer != null) "Ask again" else "Ask", enabled = canAsk) { ask() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model?.let { "ChatGPT · ${it.name}" } ?: "Not signed in yet", style = Syncr.type.caption, color = c.muted, modifier = Modifier.weight(1f))
                if (model != null) TextAction(if (account) "Hide account" else "Account", { account = !account }, enabled = !busy)
            }
            // Always composed, so it can say whether a sign-in exists; shown when there is none or when asked for.
            Box(if (account || model == null) Modifier else Modifier.heightIn(max = 0.dp).clip(RoundedCornerShape(0.dp))) {
                CompanionAccount { model = it }
            }
            if (passage == null && draft.text.isBlank()) Text("This note has no text yet. Your question is added to it and the answer goes underneath.",
                style = Syncr.type.caption, color = c.muted)
            val (words, drawing) = answer?.let { it.text to (it.svg != null) } ?: PhoneCompanion.preview(streamed)
            val has = words.isNotEmpty() || drawing
            LaunchedEffect(has) { if (has) presence.arrive() }
            // Waiting: the drifting band, until the first words.
            if (busy && !has) StippleBand()
            if (has) Column(Modifier.dissolve { presence.progress.value }.fillMaxWidth().border(1.dp, c.dots, RoundedCornerShape(12.dp)).padding(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (words.isNotEmpty()) SelectionContainer { Text(words, style = Syncr.type.body, color = c.ink) }
                if (drawing) Text(if (answer == null) "Drawing the picture…" else "With a picture.", style = Syncr.type.caption, color = c.muted)
                if (answer != null && !busy) Row { TextAction("Copy", { copyText(context, words) }) }
            }
            if (status.isNotEmpty()) Text(status, style = Syncr.type.caption, color = if (busy) c.muted else c.attention)
            Text("Only this note's text and your question are sent to OpenAI, and only when you tap Ask.", style = Syncr.type.caption, color = c.faint)
        }
    }
}

internal fun copyText(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
        .setPrimaryClip(android.content.ClipData.newPlainText("Syncr", text))
}
