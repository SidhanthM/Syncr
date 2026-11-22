package dev.syncr.prototype.notes

import android.util.Base64
import android.util.JsonWriter
import dev.syncr.core.*
import java.io.OutputStream

/** Explicit private recovery format; not a normal note or an automatic import promise. */
fun writeRecovery(output: OutputStream, data: RecoveryData) {
    JsonWriter(output.writer(Charsets.UTF_8)).use { json ->
        json.setIndent("  ")
        json.beginObject().name("format").value("syncr-recovery").name("version").value(2L)
        json.name("note_id").value(data.noteId).name("author_id").value(data.authorId)
        json.name("warning").value("Private handwriting. Pending commands are NOT applied. Automatic import/merge is not available.")
        json.name("creation")
        data.creation?.let {
            json.beginObject().name("operation_id").value(it.operationId).name("note_id").value(it.noteId)
                .name("first_sheet_id").value(it.firstSheetId).name("title").value(it.title)
                .name("notebook").value(it.notebook).endObject()
        } ?: json.nullValue()
        json.name("commands").beginArray()
        data.commands.forEach { command ->
            json.beginObject().name("operation_id").value(command.operationId).name("dependencies").beginArray()
            command.dependencies.forEach { json.value(it) }
            json.endArray().name("body").beginObject()
            when (val body = command.body) {
                is CommandBody.Marks -> {
                    json.name("type").value("Marks").name("mutations").beginArray()
                    body.mutations.forEach { mutation ->
                        json.beginObject()
                        when (mutation) {
                            is MarkMutation.AddStroke -> {
                                json.name("type").value("AddStroke").name("mark_id").value(mutation.markId)
                                    .name("sheet_id").value(mutation.sheetId).name("samples_base64")
                                    .value(Base64.encodeToString(mutation.samples, Base64.NO_WRAP))
                                json.brush(mutation.brush)
                            }
                            is MarkMutation.DeleteStroke -> {
                                json.name("type").value("DeleteStroke").name("mark_id").value(mutation.markId)
                                json.base(mutation.base)
                            }
                            is MarkMutation.RestoreStroke -> {
                                json.name("type").value("RestoreStroke").name("mark_id").value(mutation.markId)
                                json.base(mutation.base)
                            }
                            is MarkMutation.RestyleStroke -> {
                                json.name("type").value("RestyleStroke").name("mark_id").value(mutation.markId)
                                json.base(mutation.base)
                                json.brush(mutation.brush)
                            }
                            is MarkMutation.TransformStroke -> {
                                json.name("type").value("TransformStroke").name("mark_id").value(mutation.markId)
                                json.base(mutation.base)
                                val t = mutation.transform
                                json.name("transform").beginObject().name("a").value(t.a).name("b").value(t.b)
                                    .name("c").value(t.c).name("d").value(t.d).name("tx").value(t.tx).name("ty").value(t.ty).endObject()
                            }
                            // The phone never stages text edits. Fail closed rather than silently dropping an unknown mutation.
                            is MarkMutation.AddText, is MarkMutation.SpliceText, is MarkMutation.SetTextWidth,
                            is MarkMutation.DeleteText, is MarkMutation.RestoreText, is MarkMutation.TransformText,
                            is MarkMutation.RestyleText -> error("Text edits are not supported in phone recovery commands")
                            // Page commands are new (notes and canvases v1); the phone does not stage them yet.
                            is MarkMutation.AddPage, is MarkMutation.ResizePage, is MarkMutation.MovePage,
                            is MarkMutation.DeletePage, is MarkMutation.RestorePage -> error("Page edits are not supported in phone recovery commands")
                        }
                        json.endObject()
                    }
                    json.endArray()
                }
                is CommandBody.AddSheet -> {
                    json.name("type").value("AddSheet").name("sheet_id").value(body.sheetId)
                    json.base(body.base)
                }
                is CommandBody.SetNotebook -> {
                    json.name("type").value("SetNotebook").name("notebook").value(body.notebook)
                    json.base(body.base)
                }
                is CommandBody.SetArchived -> {
                    json.name("type").value("SetArchived").name("archived").value(body.archived)
                    json.base(body.base)
                }
                is CommandBody.RenameNote -> {
                    json.name("type").value("RenameNote").name("title").value(body.title)
                    json.base(body.base)
                }
                is CommandBody.ReorderSheets -> {
                    json.name("type").value("ReorderSheets").name("sheet_ids").beginArray()
                    body.sheetIds.forEach { json.value(it) }
                    json.endArray()
                    json.base(body.base)
                }
                is CommandBody.Undo -> json.name("type").value("Undo").name("target_operation_id").value(body.targetOperationId)
                is CommandBody.Redo -> json.name("type").value("Redo").name("target_operation_id").value(body.targetOperationId)
                is CommandBody.CompanionMarks, is CommandBody.SetCompanionSettings ->
                    error("Companion edits are not supported in phone recovery commands")
                is CommandBody.ImageMarks -> error("Image edits are not supported in phone recovery commands")
                is CommandBody.SetPageStyle, is CommandBody.SetCanvasBackground, is CommandBody.SetWaypoints ->
                    error("Page and canvas edits are not supported in phone recovery commands")
            }
            json.endObject().endObject()
        }
        json.endArray().name("core_backup_base64")
        data.backup?.let { json.value(Base64.encodeToString(it, Base64.NO_WRAP)) } ?: json.nullValue()
        json.name("backup_status").value(if (data.backup == null) "Unavailable; pending commands remain recoverable" else "Closed core export included")
        json.endObject()
    } // Writer flush AND close exceptions must propagate; they do not authorize closure.
}
private fun JsonWriter.base(base: Base) {
    name("base").beginObject()
    when (base) {
        is Base.Absent -> name("type").value("Absent")
        is Base.Operation -> name("type").value("Operation").name("operation_id").value(base.operationId)
        is Base.Confirmed -> name("type").value("Confirmed").name("revision").value(base.revision.toString())
    }
    endObject()
}
private fun JsonWriter.brush(brush: Brush) {
    name("brush").beginObject().name("family").value(brush.family.name).name("size").value(brush.size.name)
        .name("colour").value(brush.colour.name).endObject()
}
