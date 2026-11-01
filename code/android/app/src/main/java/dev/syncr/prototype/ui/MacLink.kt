package dev.syncr.prototype.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.link.Phase
import dev.syncr.prototype.link.android.LinkController
import dev.syncr.prototype.link.android.LinkUi
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr

/** One line for the Mac link's state, in sentence case. */
internal fun forgettableMac(ui: LinkUi): String? = ui.pairedMac

internal fun linkSummary(ui: LinkUi): String = when {
    !ui.enabled -> "Off"
    ui.status.phase == Phase.CONNECTED -> "Connected to ${ui.status.macName ?: "your Mac"}"
    ui.status.phase == Phase.NEEDS_CODE -> "Enter the code shown on your Mac"
    ui.status.phase == Phase.CONNECTING -> ui.status.message ?: "Connecting…"
    ui.pairedMac != null -> ui.status.message ?: "Looking for ${ui.pairedMac}…"
    else -> ui.status.message ?: "Not connected"
}

/** What the note's "More" sheet says about the Mac: the link's real state, and that notes are copied when it is up. */
internal fun macSectionText(ui: LinkUi): String = when {
    !ui.enabled -> "The Mac link is off. Turn it on in Settings to copy your notes to the Mac."
    ui.connected -> "Connected to ${ui.status.macName ?: "your Mac"}. This note is copied to the Mac after you change it."
    else -> "${linkSummary(ui)}. Notes are copied to the Mac once the link is up."
}

/**
 * The small connection mark for the chrome: filled in the accent when connected, hollow and quiet otherwise (the same
 * reading as the pad's caption dot). Nothing is drawn while the link is off.
 */
@Composable
fun LinkDot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ui by remember(context) { LinkController.get(context).state }.collectAsState()
    if (!ui.enabled) return
    val c = Syncr.colors
    val connected = ui.connected
    val description = "Mac link: ${linkSummary(ui)}"
    Box(modifier.size(8.dp).semantics { contentDescription = description }.then(
        if (connected) Modifier.background(c.accent, CircleShape) else Modifier.border(1.5.dp, c.faint, CircleShape)))
}

/** Settings, "Mac link": switch, found Macs, manual address, the four-digit code, connected state and forget. */
@Composable
fun MacLinkSection() {
    val c = Syncr.colors
    val context = LocalContext.current
    val link = remember(context) { LinkController.get(context) }
    val ui by remember(link) { link.state }.collectAsState()
    var address by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }

    SectionLabel("Mac link")
    Text("Copies your notes to the Syncr Mac app on this Wi-Fi network and shows your writing there as you write. " +
        "The link is not encrypted: use it on a network you trust.", style = Syncr.type.caption, color = c.muted)
    SettingsSwitch("Link to Mac", linkSummary(ui), ui.enabled) { link.setEnabled(it) }
    // Forget is local storage cleanup: it must work even if the link is off or the Mac is unreachable.
    forgettableMac(ui)?.let { mac ->
        MenuRow("Forget $mac", "Remove this phone's saved pairing so you can pair again. Notes stay on both devices.",
            tone = ActionTone.DESTRUCTIVE, onClick = { link.forget() })
    }
    if (!ui.enabled) return

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        LinkDot()
        Text(linkSummary(ui), style = Syncr.type.title, color = c.ink)
    }

    if (ui.status.phase == Phase.NEEDS_CODE) {
        Text(ui.status.message ?: "Type the four digits your Mac is showing.", style = Syncr.type.caption, color = c.muted,
            modifier = Modifier.padding(top = Space.s))
        ui.status.triesLeft?.let {
            Text(if (it == 1) "1 try left" else "$it tries left", style = Syncr.type.caption, color = c.destructive)
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            OutlinedTextField(code, { value -> code = value.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true,
                label = { Text("Code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done))
            TextAction("Pair", { link.submitCode(code); code = "" }, enabled = code.length == 4)
        }
    } else if (ui.connected) {
        Text("Notes copy to the Mac after each save, and the Mac shows the note you are writing in.",
            style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = Space.xs))
    } else {
        ui.status.message?.takeIf { ui.pairedMac == null }?.let {
            Text(it, style = Syncr.type.caption, color = c.destructive, modifier = Modifier.padding(top = Space.xs))
        }
        if (ui.found.isEmpty()) {
            Text("Looking for Macs running Syncr on this network…", style = Syncr.type.caption, color = c.muted,
                modifier = Modifier.padding(top = Space.s))
        }
        ui.found.forEach { mac ->
            MenuRow(mac.name, "${mac.host}:${mac.port}", icon = SyncrIcons.Laptop, onClick = { link.connect(mac) })
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            OutlinedTextField(address, { address = it; addressError = null }, Modifier.weight(1f), singleLine = true,
                label = { Text("Mac address") }, placeholder = { Text("192.168.1.20:5050") }, isError = addressError != null,
                supportingText = addressError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go))
            TextAction("Connect", { addressError = link.connectManual(address) }, enabled = address.isNotBlank())
        }
    }

}
