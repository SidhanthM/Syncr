package dev.syncr.prototype.notes

import android.util.Base64
import android.util.JsonWriter
import dev.syncr.core.*
import java.io.OutputStream

/** Explicit private recovery format; not a normal note or an automatic import promise. */
fun writeRecovery(output: OutputStream, data: RecoveryData) {
    JsonWriter(output.writer(Charsets.UTF_8)).use { json ->
        json.setIndent("  ")
        json.beginObject().name("format").value("syncr-recovery").name("version").value(3L)
        json.name("note_id").value(data.noteId).name("author_id").value(data.authorId)
        json.name("warning").value("Private handwriting. Pending commands are NOT applied. Automatic import/merge is not available.")
        json.name("creation")
        data.creation?.let {
            json.beginObject().name("operation_id").value(it.operationId).name("note_id").value(it.noteId)
                .name("first_sheet_id").value(it.firstSheetId).name("title").value(it.title)
                .name("notebook").value(it.notebook).name("kind").value(it.kind.name).endObject()
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
                                json.affine(mutation.transform)
                            }
                            is MarkMutation.AddText -> {
                                json.name("type").value("AddText").name("mark_id").value(mutation.markId)
                                    .name("sheet_id").value(mutation.sheetId).name("role").value(mutation.role.name)
                                    .name("origin_x").value(mutation.originX.toDouble()).name("origin_y").value(mutation.originY.toDouble())
                                    .name("width").value(mutation.width.toDouble()).name("text").value(mutation.text)
                                json.textStyle(mutation.style)
                            }
                            is MarkMutation.SpliceText -> {
                                json.name("type").value("SpliceText").name("mark_id").value(mutation.markId)
                                json.base(mutation.base)
                                json.name("offset").value(mutation.offset.toLong()).name("delete").value(mutation.delete.toLong())
                                    .name("insert").value(mutation.insert)
                            }
                            is MarkMutation.SetTextWidth -> {
                                json.name("type").value("SetTextWidth").name("mark_id").value(mutation.markId)
                                json.base(mutation.base)
                                json.name("width").value(mutation.width.toDouble())
                            }
                            is MarkMutation.DeleteText -> {
                                json.name("type").value("DeleteText").name("mark_id").value(mutation.markId)
                                json.base(mutation.base)
                            }
                            is MarkMutation.RestoreText -> {
                                json.name("type").value("RestoreText").name("mark_id").value(mutation.markId)
                                json.base(mutation.base)
                            }
                            is MarkMutation.TransformText -> {
                                json.name("type").value("TransformText").name("mark_id").value(mutation.markId)
                                json.base(mutation.base)
                                json.affine(mutation.transform)
                            }
                            is MarkMutation.RestyleText -> {
                                json.name("type").value("RestyleText").name("mark_id").value(mutation.markId)
                                json.base(mutation.base)
                                json.textStyle(mutation.style)
                            }
                            is MarkMutation.AddPage -> {
                                json.name("type").value("AddPage").name("sheet_id").value(mutation.sheetId).name("after").value(mutation.after)
                                    .name("width").value(mutation.width.toDouble()).name("height").value(mutation.height.toDouble())
                            }
                            is MarkMutation.ResizePage -> {
                                json.name("type").value("ResizePage").name("sheet_id").value(mutation.sheetId)
                                json.base(mutation.base)
                                json.name("width").value(mutation.width.toDouble()).name("height").value(mutation.height.toDouble())
                            }
                            is MarkMutation.MovePage -> {
                                json.name("type").value("MovePage").name("sheet_id").value(mutation.sheetId).name("after").value(mutation.after)
                                json.base(mutation.base)
                            }
                            is MarkMutation.DeletePage -> {
                                json.name("type").value("DeletePage").name("sheet_id").value(mutation.sheetId)
                                json.base(mutation.base)
                            }
                            is MarkMutation.RestorePage -> {
                                json.name("type").value("RestorePage").name("sheet_id").value(mutation.sheetId)
                                json.base(mutation.base)
                            }
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
                // Companion and image commands are written as their readable form rather than field by field. Never fail here:
                // a throw would lose the whole export, core backup included. Image bytes go alongside, since toString() drops them.
                is CommandBody.CompanionMarks -> json.name("type").value("CompanionMarks").name("readable").value(body.toString())
                is CommandBody.SetCompanionSettings -> {
                    json.name("type").value("SetCompanionSettings").name("readable").value(body.settings.toString())
                    json.base(body.base)
                }
                is CommandBody.ImageMarks -> {
                    json.name("type").value("ImageMarks").name("readable").value(body.toString())
                    json.name("image_bytes_base64").beginObject()
                    body.mutations.filterIsInstance<ImageMutation.Add>().forEach {
                        json.name(it.imageId).value(Base64.encodeToString(it.bytes, Base64.NO_WRAP))
                    }
                    json.endObject()
                }
                is CommandBody.SetPageStyle -> {
                    json.name("type").value("SetPageStyle").name("sheet_id").value(body.sheetId).name("template").value(body.template.name)
                    json.name("paper").value(body.paper?.argb()?.toLong()).name("pattern_colour").value(body.patternColour?.argb()?.toLong())
                    json.name("spacing").value(body.spacing?.toDouble())
                    json.base(body.base)
                }
                is CommandBody.SetCanvasBackground -> {
                    json.name("type").value("SetCanvasBackground").name("pattern").value(body.pattern.name)
                    json.name("pattern_colour").value(body.patternColour?.argb()?.toLong()).name("spacing").value(body.spacing?.toDouble())
                    json.name("paper").value(body.paper?.argb()?.toLong())
                    json.base(body.base)
                }
                is CommandBody.SetWaypoints -> {
                    json.name("type").value("SetWaypoints").name("waypoints").beginArray()
                    body.waypoints.forEach {
                        json.beginObject().name("name").value(it.name).name("cx").value(it.cx.toDouble())
                            .name("cy").value(it.cy.toDouble()).name("zoom").value(it.zoom.toDouble()).endObject()
                    }
                    json.endArray()
                    json.base(body.base)
                }
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
        .name("colour").value(brush.colour.name)
    brush.look?.let { name("look").value(it.name) }
    brush.customInk?.let { name("custom_ink").beginObject().name("l").value(it.l.toDouble()).name("c").value(it.c.toDouble())
        .name("h").value(it.h.toDouble()).endObject() }
    brush.feel?.let { name("feel").beginObject().name("pressure").value(it.pressure.toLong())
        .name("smoothing").value(it.smoothing.toLong()).name("taper").value(it.taper).endObject() }
    endObject()
}
private fun JsonWriter.textStyle(style: TextStyle) {
    name("style").beginObject().name("size").value(style.size.name).name("colour").value(style.colour.name).endObject()
}
private fun JsonWriter.affine(t: Affine) {
    name("transform").beginObject().name("a").value(t.a).name("b").value(t.b)
        .name("c").value(t.c).name("d").value(t.d).name("tx").value(t.tx).name("ty").value(t.ty).endObject()
}
