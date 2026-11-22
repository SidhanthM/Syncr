package dev.syncr.prototype.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.syncr.core.*
import dev.syncr.prototype.companion.SubscriptionClient
import dev.syncr.prototype.editor.*
import dev.syncr.prototype.notes.*
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val draftSaver = listSaver<TextDraft, String>(
    save = { listOf(it.noteId, it.blockId.orEmpty(), it.head.orEmpty(), it.text) + (it.place?.let { p -> listOf(p.sheetId, p.x.toString(), p.y.toString(), p.width.toString(), p.blockId) } ?: emptyList()) },
    restore = { TextDraft(it[0], it[1].ifEmpty { null }, it[2].ifEmpty { null }, it[3],
        if (it.size >= 9) FreePlace(it[4], it[5].toFloat(), it[6].toFloat(), it[7].toFloat(), it[8]) else null) })

/**
 * Typing on the page, Obsidian-style (editor-v2 §3 to §5.3): the field lives in [canvas]; this holds what is around
 * it. The text is stored as a splice on a throttle (the first key at once, then every ~150 ms while typing goes on, see
 * [TypingThrottle]) and when editing ends, never over a newer revision that arrived by
 * sync (then the typed text stays and a line offers Copy my text and Reload). The slash menu, `[[` completion, the
 * toolbar above the keyboard and the ask sheet are here.
 */
@Composable
internal fun MobileTextEditor(repo: NotesRepository, block: String?, canvas: NoteCanvasView?, dismiss: () -> Unit) {
    val context = LocalContext.current
    val initial = rememberSaveable(saver = draftSaver) { repo.textDraft(block, canvas?.takeFreePlace()) }
    var draft by remember { mutableStateOf(initial) }
    var state by remember { mutableStateOf(TextSel(initial.text, 0)) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var askPicture by remember { mutableStateOf(false) }
    var passage by remember { mutableStateOf<TextDraft?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var wikiClosed by remember { mutableStateOf<Int?>(null) }
    val menu = remember { SlashMenu() }
    val saving = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val notes by repo.state.collectAsState()

    /** Waits for the repository to finish with what is already staged: it takes one edit at a time. */
    suspend fun settle() { withTimeoutOrNull(5000) { repo.state.first { !it.unsaved && !it.busy } } }

    /** Stores [text] as a splice of the draft when it differs. False when it could not be stored. */
    suspend fun store(text: String): Boolean = saving.withLock {
        if (conflict) return@withLock false
        try {
            settle()
            // What is stored now: the same words under a newer head is only this editor's own last save arriving; other
            // words are another device's edit, which this one must never overwrite.
            val current = repo.textDraft(draft.blockId, draft.place)
            if (current.text == draft.text) draft = current
            else { conflict = true; error = "changed"; return@withLock false }
            if (text == draft.text) return@withLock true
            // The tap's own new last line, with nothing typed on it, is not an edit.
            if (canvas?.startedOnNewLine == true && text == draft.text + "\n") return@withLock true
            try { repo.saveText(draft, text) }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalStateException) { settle() } // staged but not delivered yet: delivery goes on in the background
            draft = repo.textDraft(draft.blockId, draft.place)
            if (draft.text != text) { error = "Could not save. Your text is still here."; return@withLock false }
            error = ""
            true
        } catch (e: CancellationException) { throw e }
        catch (e: IllegalArgumentException) { conflict = e.message?.startsWith("Wait for") != true; error = e.message.orEmpty(); false }
        catch (e: Exception) { error = e.message ?: "Could not save. Your text is still here."; false }
    }
    suspend fun flush(): Boolean = store(canvas?.currentText()?.text ?: draft.text)

    SideEffect { canvas?.setTextEditingEnabled(!asking) }
    DisposableEffect(canvas, initial.noteId) {
        canvas?.beginTextEditing(initial, initial.text) { next, undo, redo -> state = next; canUndo = undo; canRedo = redo }
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) scope.launch { flush() } }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            // The note is closing or editing ended elsewhere: store what is typed, outside this composition's scope.
            val last = canvas?.currentText()?.text
            canvas?.endTextEditing()
            if (last != null) CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { store(last) }
        }
    }
    // Debug builds only: a broadcast stores another "device's" edit under the open field, to show the conflict line.
    DisposableEffect(initial.noteId) {
        if (!debugHooksAllowed(context)) return@DisposableEffect onDispose {}
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                scope.launch {
                    val stored = repo.textDraft(draft.blockId, draft.place)
                    runCatching { repo.saveText(stored, stored.text + "\nedited elsewhere") }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
            android.content.IntentFilter("dev.syncr.prototype.DEBUG_FOREIGN_EDIT"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    // [0] is when the oldest unsaved keystroke arrived and [1] when the text was last stored; a keystroke restarts the effect
    // below but not these, so the deadline stays where it was and a long burst is still stored every ~150 ms.
    val clock = remember { longArrayOf(0L, 0L) }
    LaunchedEffect(state.text) {
        if (!conflict && state.text != draft.text) {
            val now = android.os.SystemClock.uptimeMillis()
            if (clock[0] == 0L) clock[0] = now
            delay(TypingThrottle.delayMs(now, clock[0], clock[1]))
            // The next keystroke restarts this effect; a save already begun must still finish.
            withContext(NonCancellable) { clock[0] = 0L; clock[1] = android.os.SystemClock.uptimeMillis(); flush() }
        }
    }

    // What the caret is in: a slash token, or a `[[` link.
    val slash = remember(tick, state) { menu.update(state) }
    val wiki = remember(state) { if (state.caret) Wiki.token(state.text, state.start)?.takeIf { it.start != wikiClosed } else null }
    val titles = remember(notes.notes, wiki) { if (wiki == null) emptyList() else Wiki.matches(wiki.query, notes.notes.filter { it.id != initial.noteId }.map { it.title }) }
    val rows = when { slash != null -> slashRows(slash.rows); wiki != null -> titles.map { PanelRow("[[", it, "") }; else -> emptyList() }
    val selected = slash?.selected ?: 0

    fun startAsk(picture: Boolean) {
        scope.launch {
            val ok = flush()
            if (!ok) return@launch
            passage = repo.textDraft(draft.blockId, draft.place); askPicture = picture; asking = true
        }
    }
    fun pick(index: Int) {
        val live = canvas?.currentText() ?: return
        // Rows come from the text as it is now, not from the last frame the panel drew.
        val liveSlash = if (live.caret) Slash.token(live.text, live.start) else null
        val liveWiki = if (live.caret) Wiki.token(live.text, live.start) else null
        if (liveSlash != null) {
            val command = Slash.matches(liveSlash.query).getOrNull(index) ?: return
            val now = java.time.LocalTime.now()
            val result = Slash.run(live, liveSlash, command, java.time.LocalDate.now().toString(), "%02d:%02d".format(now.hour, now.minute))
            canvas.applyText(result.edit)
            when (result.action) { SlashAction.ASK -> startAsk(false); SlashAction.DRAW -> startAsk(true); else -> Unit }
        } else if (liveWiki != null) {
            val others = notes.notes.filter { it.id != initial.noteId }.map { it.title }
            Wiki.matches(liveWiki.query, others).getOrNull(index)?.let { canvas.applyText(Wiki.complete(live, liveWiki, it)) }
        }
    }
    fun apply(next: (TextSel) -> TextSel) { canvas?.let { it.applyText(next(it.currentText())) } }
    fun openSlash() {
        val live = canvas?.currentText() ?: return
        val lead = if (live.lo > 0 && !live.text[live.lo - 1].isWhitespace()) " /" else "/"
        canvas.applyText(TextSel(live.text.substring(0, live.lo) + lead + live.text.substring(live.hi), live.lo + lead.length))
    }
    fun finish() { scope.launch { if (flush()) dismiss() } }

    // Back closes the menu first, then ends editing (the note stays open); a second Back leaves the note.
    androidx.activity.compose.BackHandler(enabled = !asking) { if (!conflict) finish() }
    androidx.activity.compose.BackHandler(enabled = rows.isNotEmpty() && !asking) {
        if (slash != null) { menu.escape(); tick++ } else if (wiki != null) wikiClosed = wiki.start
    }

    val formatOn = { f: Typing.Format -> Typing.active(state, f) }
    val buttons = listOf(
        ToolButton(SyncrIcons.Undo, "Undo", enabled = canUndo) { canvas?.undoText() },
        ToolButton(SyncrIcons.Redo, "Redo", enabled = canRedo) { canvas?.redoText() },
        ToolButton(SyncrIcons.Slash, "Slash commands") { openSlash() },
        ToolButton(SyncrIcons.Heading, "Heading") { apply(Typing::cycleHeading) },
        ToolButton(SyncrIcons.Bold, "Bold", on = formatOn(Typing.Format.BOLD)) { apply { Typing.toggle(it, Typing.Format.BOLD) } },
        ToolButton(SyncrIcons.Italic, "Italic", on = formatOn(Typing.Format.ITALIC)) { apply { Typing.toggle(it, Typing.Format.ITALIC) } },
        ToolButton(SyncrIcons.Bullet, "Bulleted list") { apply(Typing::toggleBullet) },
        ToolButton(SyncrIcons.Todo, "To-do") { apply(Typing::toggleTask) },
        ToolButton(SyncrIcons.Outdent, "Outdent") { apply { Typing.tab(it, outdent = true) } },
        ToolButton(SyncrIcons.Indent, "Indent") { apply { Typing.tab(it, outdent = false) } },
        ToolButton(SyncrIcons.Spark, "Ask Syncr") { startAsk(false) },
        ToolButton(SyncrIcons.HideKeyboard, "Hide keyboard") { finish() },
    )
    val c = Syncr.colors
    Column(Modifier.fillMaxWidth().onSizeChanged { canvas?.setTextInset(it.height) }) {
        if (conflict) Row(Modifier.fillMaxWidth().background(c.attentionSoft).padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically) {
            Text("This text changed on another device. Yours is still here.", style = Syncr.type.caption, color = c.ink, modifier = Modifier.weight(1f).padding(vertical = Space.s))
            TextAction("Copy my text", { copyText(context, canvas?.currentText()?.text.orEmpty()) })
            TextAction("Reload", {
                scope.launch {
                    val fresh = repo.textDraft(draft.blockId, draft.place)
                    draft = fresh; conflict = false; error = ""; canvas?.resetText(fresh.text)
                }
            })
        } else if (error.isNotEmpty()) Text(error, style = Syncr.type.caption, color = c.attention, modifier = Modifier.fillMaxWidth().background(c.attentionSoft).padding(horizontal = Space.l, vertical = Space.s))
        EditorPanel(rows.isNotEmpty() && !asking, rows, selected, grouped = slash != null && slash.token.query.isEmpty(), onPick = ::pick)
        EditorToolbar(buttons)
    }
    if (asking) passage?.let { saved ->
        AskCompanion(repo, repo.state.value.snapshot?.title.orEmpty(), saved, state.lo, state.hi, initialPicture = askPicture,
            dismiss = { asking = false }, saved = { asking = false; dismiss() })
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
    SyncrDialog(onDismiss = { if (!busy) dismiss() }, title = "Response", confirm = { TextAction("Close", dismiss, enabled = !busy) }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            SelectionContainer { DialogText(text.ifEmpty { "This response is no longer available." }) }
            val sources = (margin?.sources.orEmpty() + messages.flatMap { it.sources }).distinctBy { it.url }
            sources.forEach { source -> TextAction(source.url, {
                val uri = Uri.parse(source.url)
                if (uri.scheme in listOf("https", "http")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }) }
            if (error.isNotEmpty()) Text(error, style = Syncr.type.caption, color = Syncr.colors.destructive)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                TextAction("Copy", { copy(context, text) }, enabled = text.isNotEmpty())
                TextAction("Add to note", { run { repo.saveText(destination, destination.text.trimEnd() + "\n\n" + text + "\n") } },
                    enabled = !busy && text.isNotEmpty())
                if (margin != null && item != null) {
                    TextAction(if (margin.state == MarginState.PINNED) "Unpin" else "Pin", { run { repo.setResponseState(note.noteId, id, item.head.operationId,
                        if (margin.state == MarginState.PINNED) MarginState.OPEN else MarginState.PINNED) } }, enabled = !busy)
                    TextAction("Dismiss response", { run { repo.setResponseState(note.noteId, id, item.head.operationId, MarginState.DISMISSED) } }, enabled = !busy)
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) = copyText(context, text)
