package dev.syncr.prototype.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget

/** Small quiet label, sentence case. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Syncr.colors.muted) {
    Text(text, modifier, color = color, style = Syncr.type.label)
}

/** A section heading inside a sheet; announced as a heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Label(text, modifier.semantics { heading() }.padding(top = Space.xl, bottom = Space.s))
}

/** A full-width 1dp rule in the line colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Syncr.colors.dots) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** How strongly a [TextAction] speaks. */
enum class ActionTone { DEFAULT, DESTRUCTIVE }

/** A text button with a 48dp target. Disabled actions stay legible (muted, not faded away). */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    tone: ActionTone = ActionTone.DEFAULT, description: String? = null) {
    val c = Syncr.colors
    val colour = when {
        !enabled -> c.faint
        tone == ActionTone.DESTRUCTIVE -> c.destructive
        else -> c.ink
    }
    Box(modifier.heightIn(min = TouchTarget).clip(Radius.pill)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .padding(horizontal = Space.m), contentAlignment = Alignment.Center) {
        Text(text, style = Syncr.type.label.copy(fontWeight = FontWeight.SemiBold), color = colour, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** The one filled button of a sheet: ink on the page, as the selected chip is. */
@Composable
fun PrimaryAction(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Syncr.colors
    Box(modifier.heightIn(min = TouchTarget).clip(Radius.pill).background(if (enabled) c.ink else c.dots)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = Space.xl),
        contentAlignment = Alignment.Center) {
        Text(text, style = Syncr.type.label.copy(fontWeight = FontWeight.SemiBold), color = if (enabled) c.surface1 else c.muted, maxLines = 1)
    }
}

/** A 48dp icon-only button; [label] is spoken. */
@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, tint: Color = Syncr.colors.ink) {
    Box(modifier.size(TouchTarget).clip(Radius.pill)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        SyncrIcon(icon, if (enabled) tint else Syncr.colors.faint, size = 22.dp)
    }
}

/** A full-width tappable row for sheets: title, an optional detail line, and an optional leading icon. */
@Composable
fun MenuRow(title: String, subtitle: String? = null, enabled: Boolean = true, icon: ImageVector? = null,
    tone: ActionTone = ActionTone.DEFAULT, onClick: () -> Unit) {
    val c = Syncr.colors
    val colour = when {
        !enabled -> c.faint
        tone == ActionTone.DESTRUCTIVE -> c.destructive
        else -> c.ink
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Radius.s)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            SyncrIcon(icon, colour, size = 22.dp)
            Spacer(Modifier.width(Space.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Syncr.type.body, color = colour)
            if (subtitle != null) Text(subtitle, style = Syncr.type.caption, color = if (enabled) c.muted else c.faint)
        }
    }
}

/**
 * A notebook chip (design `.chip`): a pill with a strong hairline; the selected one is filled with ink.
 * [quiet] chips (Archived, Recently deleted) drop the outline so they read as secondary places.
 * The visible pill is 32dp tall inside a 48dp target.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null,
    description: String? = null, enabled: Boolean = true, quiet: Boolean = false, onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = Syncr.colors
    val content = when {
        selected -> c.surface1
        !enabled -> c.faint
        quiet -> c.muted
        else -> c.ink
    }
    // The press fill belongs to the visible pill, not the 48dp target around it.
    val press = remember { MutableInteractionSource() }
    Box(modifier.heightIn(min = TouchTarget)
        .combinedClickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Tab,
            onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick)
        .semantics(mergeDescendants = true) {
            if (description != null) contentDescription = description
            this.selected = selected
        }, contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 32.dp).clip(Radius.pill).indication(press, LocalIndication.current)
            .then(when {
                selected -> Modifier.background(c.ink, Radius.pill)
                quiet -> Modifier
                else -> Modifier.border(1.dp, c.lineStrong, Radius.pill)
            })
            .padding(horizontal = if (icon != null && text.isEmpty()) Space.s else Space.m, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) SyncrIcon(icon, content, size = 18.dp)
            if (text.isNotEmpty()) Text(text, style = Syncr.type.label, color = content, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.sizeIn(maxWidth = 200.dp))
        }
    }
}

/**
 * A calm empty state: a small hand-drawn picture ([art], drawn on once) or else a faint icon, one line saying what this
 * place is for, and an optional hint.
 */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String?, modifier: Modifier = Modifier, art: List<ArtStroke>? = null) {
    val c = Syncr.colors
    Column(modifier.fillMaxWidth().padding(vertical = Space.xxl, horizontal = Space.l)
        .semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (art != null) EmptyArt(art, Modifier.size(width = 120.dp, height = 90.dp)) else SyncrIcon(icon, c.faint, size = 32.dp)
        Text(title, style = Syncr.type.title, color = c.ink, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Space.m))
        if (hint != null) Text(hint, style = Syncr.type.caption, color = c.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Space.xs))
    }
}
