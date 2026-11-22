package dev.syncr.prototype.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syncr.core.PagePattern
import dev.syncr.core.PageView
import dev.syncr.prototype.notes.NewNoteChoice
import dev.syncr.prototype.notes.NewNoteSizes
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget

/** Paper colours offered next to "Default" (the theme's own paper). */
private val PAPERS = listOf("White" to 0xffffff, "Cream" to 0xfbf6e9, "Sepia" to 0xf3e9d2, "Mist" to 0xeef1f4, "Grey" to 0xe9e9e9)

/** Pattern colours offered next to "Default" (grey). */
private val PATTERNS = listOf("Blue" to 0x4a74b8, "Red" to 0xb85a5a, "Green" to 0x5a9b6a, "Ink" to 0x333333)

/**
 * The New note screen (xnotes' `NewNoteScreen` without cover and template gallery): a header with close, "New note in
 * <notebook>", the name and Create; the page size, orientation and template; the paper colour; the pattern colour and
 * opacity. A small page preview shows the choices. Stacked on a phone, side by side from 720 dp wide.
 */
@Composable
fun NewNoteScreen(notebook: String, initialName: String, initial: NewNoteChoice, onClose: () -> Unit,
    onCreate: (name: String, choice: NewNoteChoice) -> Unit) {
    val c = Syncr.colors
    var choice by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length))) }
    val focus = remember { FocusRequester() }
    var selectedOnFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val problem = choice.problem()
    val create = { if (problem == null) onCreate(name.text.trim().ifEmpty { initialName }, choice) }
    // A full-screen layer of the app window (not a Dialog window), so the status bar, cutout and keyboard insets apply.
    androidx.activity.compose.BackHandler(onBack = onClose)
    run {
        Column(Modifier.fillMaxSize().background(c.desk).pointerInput(Unit) { detectTapGestures { } }.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                IconAction(SyncrIcons.Close, "Close", onClose)
                Text("New note in $notebook", style = Syncr.type.label, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = Space.s))
                PrimaryAction("Create", enabled = problem == null, modifier = Modifier.padding(end = Space.s), onClick = create)
            }
            BasicName(name, { name = it }, focus, onDone = create, selectOnFocus = !selectedOnFocus, onFocused = { selectedOnFocus = true },
                modifier = Modifier.padding(horizontal = Space.page))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 720.dp
                if (wide) Row(Modifier.fillMaxSize().padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    Box(Modifier.weight(0.38f).fillMaxHeight().padding(vertical = Space.l), contentAlignment = Alignment.Center) {
                        PagePreview(choice, Modifier.fillMaxWidth().height(this@BoxWithConstraints.maxHeight * 0.7f))
                    }
                    Column(Modifier.weight(0.62f).verticalScroll(rememberScrollState()).padding(vertical = Space.l)) { Controls(choice, problem) { choice = it } }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.page, vertical = Space.l)) {
                    PagePreview(choice, Modifier.fillMaxWidth().height(180.dp))
                    Column(Modifier.padding(top = Space.l)) { Controls(choice, problem) { choice = it } }
                }
            }
        }
    }
}

/** The name: large, one line, text selected the first time it takes focus, Done creates. */
@Composable
private fun BasicName(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, focus: FocusRequester, onDone: () -> Unit,
    selectOnFocus: Boolean, onFocused: () -> Unit, modifier: Modifier) {
    var held by remember { mutableStateOf(value) }
    if (held.text != value.text) held = value
    SyncrTextField(held, { held = it; onChange(it) }, modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { if (it.isFocused) onFocused() },
        label = "Name", singleLine = true, textStyle = Syncr.type.title,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }))
}

@Composable
private fun Controls(choice: NewNoteChoice, problem: String?, onChange: (NewNoteChoice) -> Unit) {
    val c = Syncr.colors
    PageSizeFields(choice, problem, onChange)

    SectionLabel("Template", Modifier.padding(top = Space.l))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        for ((label, pattern) in listOf("None" to PagePattern.NONE, "Lines" to PagePattern.LINES, "Dots" to PagePattern.DOTS, "Grid" to PagePattern.GRID))
            Chip(label, choice.template == pattern, description = "Template $label") { onChange(choice.copy(template = pattern)) }
    }

    SectionLabel("Paper colour", Modifier.padding(top = Space.l))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        Chip("Default", choice.paper == null, description = "Default paper") { onChange(choice.copy(paper = null)) }
        for ((label, rgb) in PAPERS) Swatch(label, Color(0xff000000.toInt() or rgb), choice.paper == rgb) { onChange(choice.copy(paper = rgb)) }
    }

    if (choice.template != PagePattern.NONE) {
        SectionLabel("Pattern colour", Modifier.padding(top = Space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Chip("Default", choice.patternColour == null, description = "Default pattern colour") { onChange(choice.copy(patternColour = null)) }
            for ((label, rgb) in PATTERNS) Swatch(label, Color(0xff000000.toInt() or rgb), choice.patternColour == rgb) { onChange(choice.copy(patternColour = rgb)) }
        }
        SettingsSlider("Opacity", choice.opacity, "Faint", "Strong", "${Math.round(choice.opacity * 100)}%") { onChange(choice.copy(opacity = it)) }
    }
}

/** Page size, the Custom millimetre fields with their range error, and portrait or landscape; the New note screen and "Page size…" share it. */
@Composable
internal fun PageSizeFields(choice: NewNoteChoice, problem: String?, onChange: (NewNoteChoice) -> Unit) {
    val c = Syncr.colors
    SectionLabel("Page size")
    SizeDropdown(choice) { onChange(choice.copy(size = it)) }
    if (choice.custom) Row(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        SyncrTextField(choice.customWidth, { onChange(choice.copy(customWidth = it)) }, Modifier.weight(1f), label = "Width (mm)", singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next))
        SyncrTextField(choice.customHeight, { onChange(choice.copy(customHeight = it)) }, Modifier.weight(1f), label = "Height (mm)", singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done))
    }
    if (problem != null) Text(problem, style = Syncr.type.caption, color = c.destructive, modifier = Modifier.padding(top = Space.xs))
    Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        val landscape = choice.millimetres()?.let { it.first > it.second } ?: choice.landscape
        Chip("Portrait", !landscape, description = "Portrait") { onChange(choice.withOrientation(false)) }
        Chip("Landscape", landscape, description = "Landscape") { onChange(choice.withOrientation(true)) }
    }
}

/** The size row: the current size and its millimetres, a menu of the others and Custom. */
@Composable
private fun SizeDropdown(choice: NewNoteChoice, onPick: (String) -> Unit) {
    val c = Syncr.colors
    var open by remember { mutableStateOf(false) }
    val mm = choice.millimetres()
    Box {
        Row(Modifier.fillMaxWidth().heightIn48().clip(Radius.m).background(c.surface1, Radius.m).border(BorderStroke(1.dp, c.dots), Radius.m)
            .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = Space.m)
            .semantics { contentDescription = "Page size ${choice.size}" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(choice.size, style = Syncr.type.body, color = c.ink)
            if (!choice.custom && mm != null) Text("${fmt(mm.first)} × ${fmt(mm.second)} mm", style = Syncr.type.caption, color = c.muted, modifier = Modifier.weight(1f))
            else Box(Modifier.weight(1f))
            SyncrIcon(SyncrIcons.Chevron, c.muted, size = 18.dp)
        }
        SyncrMenu(open, { open = false }) {
            for (o in NewNoteSizes.named) MenuItem("${o.name}   ${fmt(o.mmWidth)} × ${fmt(o.mmHeight)} mm", { open = false; onPick(o.name) })
            MenuItem(NewNoteSizes.CUSTOM, { open = false; onPick(NewNoteSizes.CUSTOM) })
        }
    }
}

private fun Modifier.heightIn48() = this.then(Modifier.height(TouchTarget))

private fun fmt(mm: Float) = if (mm == mm.toInt().toFloat()) mm.toInt().toString() else "%.1f".format(mm)

/** A round colour swatch, ringed when chosen. */
@Composable
private fun Swatch(label: String, colour: Color, selected: Boolean, onClick: () -> Unit) {
    val c = Syncr.colors
    Box(Modifier.size(TouchTarget).clip(CircleShape).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = label; this.selected = selected }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).background(colour, CircleShape).border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.lineStrong, CircleShape))
    }
}

/** The chosen page in miniature: its shape, paper and template. */
@Composable
private fun PagePreview(choice: NewNoteChoice, modifier: Modifier) {
    val c = Syncr.colors
    val mm = choice.millimetres()?.takeIf { choice.problem() == null } ?: (210f to 297f)
    val aspect = mm.first / mm.second
    Box(modifier, contentAlignment = Alignment.Center) {
        val paper = choice.paper?.let { Color(0xff000000.toInt() or it) } ?: c.paper
        val ink = (choice.patternColour ?: NewNoteChoice.DEFAULT_PATTERN).let { Color(0xff000000.toInt() or it) }.copy(alpha = choice.opacity)
        Canvas(Modifier.fillMaxHeight().aspectRatio(aspect.coerceIn(0.2f, 5f), matchHeightConstraintsFirst = true)
            .background(paper).border(1.dp, c.lineStrong)) {
            // About 8 mm between rulings, however big the page.
            val step = size.width / (mm.first / 8f).coerceAtLeast(4f)
            when (choice.template) {
                PagePattern.LINES -> { var y = step; while (y < size.height) { drawLine(ink, Offset(0f, y), Offset(size.width, y), 1f); y += step } }
                PagePattern.GRID -> {
                    var y = step; while (y < size.height) { drawLine(ink, Offset(0f, y), Offset(size.width, y), 1f); y += step }
                    var x = step; while (x < size.width) { drawLine(ink, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                }
                PagePattern.DOTS -> {
                    var y = step; while (y < size.height) { var x = step; while (x < size.width) { drawCircle(ink, 1.6f, Offset(x, y)); x += step }; y += step }
                }
                PagePattern.NONE -> Unit
            }
        }
    }
}

/** The chooser's starting point for [page]: its named size (either way round), else Custom in millimetres. */
internal fun choiceFor(page: PageView): NewNoteChoice {
    val named = try { dev.syncr.core.matchSize(page.width, page.height) } catch (_: Throwable) { null }
    val landscape = page.width > page.height
    if (named != null) return NewNoteChoice(size = named.name, landscape = landscape)
    fun mm(units: Float) = "%.1f".format(units / NewNoteSizes.UNITS_PER_MM).trimEnd('0').trimEnd('.')
    return NewNoteChoice(size = NewNoteSizes.CUSTOM, landscape = landscape, customWidth = mm(page.width), customHeight = mm(page.height))
}
