package dev.syncr.prototype.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.syncr.prototype.link.Phase
import dev.syncr.prototype.link.android.LinkController
import dev.syncr.prototype.link.android.LinkUi
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import kotlinx.coroutines.delay

/** What the pairing screen is showing, from the link's state (remaining-v1 P.1). */
internal enum class PairingStage { CONNECTING, CODE, PAIRED, FAILED, PROBLEM }

internal fun pairingStage(ui: LinkUi): PairingStage? {
    val target = ui.pairing ?: return null
    return when {
        target.problem != null -> PairingStage.PROBLEM
        ui.status.phase == Phase.CONNECTED && ui.status.macName == target.name -> PairingStage.PAIRED
        ui.status.phase == Phase.NEEDS_CODE -> PairingStage.CODE
        ui.status.phase == Phase.IDLE && ui.status.message != null -> PairingStage.FAILED
        else -> PairingStage.CONNECTING
    }
}

internal fun pairingLine(ui: LinkUi, stage: PairingStage): String = when (stage) {
    PairingStage.PROBLEM -> ui.pairing?.problem ?: ""
    PairingStage.PAIRED -> "Paired. Your notes now copy to ${ui.pairing?.name ?: "the Mac"}."
    PairingStage.CODE -> "Enter the code shown on your Mac"
    PairingStage.FAILED -> ui.status.message ?: "Could not reach the Mac."
    PairingStage.CONNECTING -> "Connecting to ${ui.pairing?.name ?: "the Mac"}…"
}

/**
 * The pairing screen: the Mac's name, four boxes and a number pad. Opened by a scanned `syncr://pair` link or by tapping a Mac in
 * Settings; it types the four digits the Mac shows. Closes itself shortly after the pairing worked.
 */
@Composable
fun PairingHost() {
    val context = LocalContext.current
    val link = remember(context) { LinkController.get(context) }
    val ui by remember(link) { link.state }.collectAsState()
    val stage = pairingStage(ui) ?: return
    Dialog(onDismissRequest = link::endPairing,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SheetSystemBars(Syncr.colors.dark)
        PairingScreen(ui, stage, onDigits = link::submitCode, onClose = link::endPairing,
            onRetry = { ui.pairing?.endpoint?.let(link::beginPairing) })
    }
}

@Composable
internal fun PairingScreen(ui: LinkUi, stage: PairingStage, onDigits: (String) -> Unit, onClose: () -> Unit, onRetry: () -> Unit) {
    val c = Syncr.colors
    var digits by remember { mutableStateOf("") }
    // A wrong code empties the boxes; the Mac's answer says how many tries are left.
    LaunchedEffect(ui.status.triesLeft, stage) { if (stage != PairingStage.PAIRED) digits = "" }
    LaunchedEffect(stage) { if (stage == PairingStage.PAIRED) { delay(1_600); onClose() } }
    val name = ui.pairing?.name ?: "your Mac"
    Box(Modifier.fillMaxSize().background(c.paper)) {
        IconAction(SyncrIcons.Close, "Close", onClose, Modifier.align(Alignment.TopStart).padding(Space.s))
        Column(Modifier.align(Alignment.Center).widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Pair with $name", style = Syncr.type.subheading, color = c.ink, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() })
            Text(pairingLine(ui, stage), style = Syncr.type.caption, color = if (stage == PairingStage.PROBLEM || stage == PairingStage.FAILED) c.destructive else c.muted,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.xs))
            ui.status.triesLeft?.takeIf { stage == PairingStage.CODE }?.let {
                Text((ui.status.message ?: "That code was wrong.") + " " + if (it == 1) "1 try left." else "$it tries left.",
                    style = Syncr.type.caption, color = c.destructive, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.xs))
            }
            Spacer(Modifier.height(Space.xl))
            if (stage != PairingStage.PROBLEM) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    for (i in 0 until 4) DigitBox(digits.getOrNull(i), active = stage == PairingStage.CODE && i == digits.length.coerceAtMost(3), done = stage == PairingStage.PAIRED)
                }
                Spacer(Modifier.height(Space.xl))
                Keypad(enabled = stage == PairingStage.CODE) { key ->
                    when {
                        key == BACKSPACE -> digits = digits.dropLast(1)
                        digits.length < 4 -> {
                            digits += key
                            if (digits.length == 4) onDigits(digits)
                        }
                    }
                }
                if (stage == PairingStage.FAILED) TextAction("Try again", onRetry, Modifier.padding(top = Space.l))
            } else {
                TextAction("Close", onClose, Modifier.padding(top = Space.l))
            }
        }
    }
}

private const val BACKSPACE = "<"

@Composable
private fun DigitBox(digit: Char?, active: Boolean, done: Boolean) {
    val c = Syncr.colors
    Box(Modifier.size(width = 56.dp, height = 68.dp)
        .background(c.surface1, Radius.m)
        .border(if (active) 2.dp else 1.dp, if (active) c.ink else c.lineStrong, Radius.m)
        .semantics { contentDescription = if (digit == null) "empty" else "digit entered" },
        contentAlignment = Alignment.Center) {
        if (digit != null) Text(digit.toString(), color = if (done) c.accent else c.ink,
            style = Syncr.type.heading.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 32.sp))
    }
}

@Composable
private fun Keypad(enabled: Boolean, onKey: (String) -> Unit) {
    val c = Syncr.colors
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", BACKSPACE))
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        for (row in rows) Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            for (key in row) {
                val label = if (key == BACKSPACE) "Delete" else key
                Box(Modifier.size(width = 88.dp, height = 56.dp).then(
                        if (key.isEmpty()) Modifier else Modifier.background(c.surface2, Radius.m)
                            .clickable(enabled = enabled, role = Role.Button) { onKey(key) }
                            .semantics { contentDescription = label }),
                    contentAlignment = Alignment.Center) {
                    when (key) {
                        "" -> Unit
                        BACKSPACE -> SyncrIcon(SyncrIcons.Back, if (enabled) c.ink else c.faint, size = 22.dp)
                        else -> Text(key, style = Syncr.type.subheading, color = if (enabled) c.ink else c.faint)
                    }
                }
            }
        }
    }
}
