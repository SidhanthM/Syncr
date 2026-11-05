//! SYNO v1 canonical operation bytes: the identity of an operation for idempotency
//! (an ID retried with different bytes is an `IdCollision`). Never JSON equality. The
//! byte grammar is in README.md; any change here breaks every stored operation.
use crate::{error::limit, samples::MAX_SAMPLE_BYTES, *};

/// Budget for everything except sample bytes: prefix, tags, counts, strings, lengths.
const MAX_METADATA_BYTES: usize = 64 * 1024;
const PREFIX: &[u8] = b"SYNO\x01\x00";

struct Encoder {
    bytes: Vec<u8>,
    metadata: usize,
    samples: usize,
    text: usize,
}
impl Encoder {
    fn new() -> Self {
        Self {
            bytes: PREFIX.to_vec(),
            metadata: PREFIX.len(),
            samples: 0,
            text: 0,
        }
    }
    fn raw(&mut self, bytes: &[u8]) {
        self.bytes.extend_from_slice(bytes);
        self.metadata += bytes.len();
    }
    fn tag(&mut self, tag: u8) {
        self.raw(&[tag]);
    }
    fn length(&mut self, len: usize) -> CoreResult<()> {
        let len = u32::try_from(len).map_err(|_| limit("Operation exceeds size limit"))?;
        self.raw(&len.to_le_bytes());
        Ok(())
    }
    fn string(&mut self, value: &str) -> CoreResult<()> {
        if value.len() > MAX_METADATA_BYTES
            || self.metadata.saturating_add(4).saturating_add(value.len()) > MAX_METADATA_BYTES
        {
            return Err(limit("Operation metadata exceeds 64 KiB"));
        }
        self.length(value.len())?;
        self.raw(value.as_bytes());
        Ok(())
    }
    fn blob(&mut self, value: &[u8]) -> CoreResult<()> {
        self.length(value.len())?;
        self.samples = self
            .samples
            .checked_add(value.len())
            .ok_or_else(|| limit("Gesture samples exceed size limit"))?;
        if self.samples > MAX_SAMPLE_BYTES {
            return Err(limit("Gesture samples exceed 16 MiB"));
        }
        self.bytes.extend_from_slice(value);
        Ok(())
    }
    fn text(&mut self, value: &str) -> CoreResult<()> {
        if value.len() > crate::text::MAX_TEXT_BYTES {
            return Err(limit("Text exceeds 1 MiB"));
        }
        self.text = self.text.saturating_add(value.len());
        if self.text > crate::text::MAX_GESTURE_TEXT_BYTES {
            return Err(limit("Gesture text exceeds 16 MiB"));
        }
        self.length(value.len())?;
        self.bytes.extend_from_slice(value.as_bytes());
        Ok(())
    }
    fn text_style(&mut self, style: &TextStyle) {
        self.tag(match style.size {
            BrushSize::Small => 0,
            BrushSize::Medium => 1,
            BrushSize::Large => 2,
        });
        self.tag(match style.colour {
            InkColour::Ink => 0,
            InkColour::Red => 1,
            InkColour::Green => 2,
            InkColour::Blue => 3,
            InkColour::Orange => 4,
        });
    }
    fn optional(&mut self, value: Option<&str>) -> CoreResult<()> {
        match value {
            None => self.tag(0),
            Some(value) => {
                self.tag(1);
                self.string(value)?;
            }
        }
        Ok(())
    }
    fn base(&mut self, base: &Base) -> CoreResult<()> {
        match base {
            Base::Absent => self.tag(0),
            Base::Operation { operation_id } => {
                self.tag(1);
                self.string(operation_id)?;
            }
            Base::Confirmed { revision } => {
                self.tag(2);
                self.raw(&revision.to_le_bytes());
            }
        }
        Ok(())
    }
    fn brush(&mut self, brush: &Brush) {
        self.tag(match brush.family {
            BrushFamily::Pen => 0,
            BrushFamily::Highlighter => 1,
        });
        self.tag(match brush.size {
            BrushSize::Small => 0,
            BrushSize::Medium => 1,
            BrushSize::Large => 2,
        });
        self.tag(match brush.colour {
            InkColour::Ink => 0,
            InkColour::Red => 1,
            InkColour::Green => 2,
            InkColour::Blue => 3,
            InkColour::Orange => 4,
        });
    }
    fn mutation(&mut self, m: &MarkMutation) -> CoreResult<()> {
        match m {
            MarkMutation::AddStroke {
                mark_id,
                sheet_id,
                samples,
                brush,
            } => {
                self.tag(0);
                self.string(mark_id)?;
                self.string(sheet_id)?;
                self.blob(samples)?;
                self.brush(brush);
            }
            MarkMutation::DeleteStroke { mark_id, base } => {
                self.tag(1);
                self.string(mark_id)?;
                self.base(base)?;
            }
            MarkMutation::RestoreStroke { mark_id, base } => {
                self.tag(2);
                self.string(mark_id)?;
                self.base(base)?;
            }
            MarkMutation::TransformStroke {
                mark_id,
                base,
                transform: t,
            } => {
                self.tag(3);
                self.string(mark_id)?;
                self.base(base)?;
                // Exact IEEE bits: -0.0 and 0.0 are different operations.
                for v in [t.a, t.b, t.c, t.d, t.tx, t.ty] {
                    self.raw(&v.to_bits().to_le_bytes());
                }
            }
            MarkMutation::RestyleStroke {
                mark_id,
                base,
                brush,
            } => {
                self.tag(4);
                self.string(mark_id)?;
                self.base(base)?;
                self.brush(brush);
            }
            MarkMutation::AddText {
                mark_id,
                sheet_id,
                role,
                origin_x,
                origin_y,
                width,
                text,
                style,
            } => {
                self.tag(5);
                self.string(mark_id)?;
                self.string(sheet_id)?;
                self.tag(match role {
                    TextRole::Document => 0,
                    TextRole::Free => 1,
                });
                for value in [origin_x, origin_y, width] {
                    self.raw(&value.to_bits().to_le_bytes());
                }
                self.text(text)?;
                self.text_style(style);
            }
            MarkMutation::SpliceText {
                mark_id,
                base,
                offset,
                delete,
                insert,
            } => {
                self.tag(6);
                self.string(mark_id)?;
                self.base(base)?;
                self.raw(&offset.to_le_bytes());
                self.raw(&delete.to_le_bytes());
                self.text(insert)?;
            }
            MarkMutation::SetTextWidth {
                mark_id,
                base,
                width,
            } => {
                self.tag(7);
                self.string(mark_id)?;
                self.base(base)?;
                self.raw(&width.to_bits().to_le_bytes());
            }
            MarkMutation::DeleteText { mark_id, base }
            | MarkMutation::RestoreText { mark_id, base } => {
                self.tag(if matches!(m, MarkMutation::DeleteText { .. }) {
                    8
                } else {
                    9
                });
                self.string(mark_id)?;
                self.base(base)?;
            }
            MarkMutation::TransformText {
                mark_id,
                base,
                transform: t,
            } => {
                self.tag(10);
                self.string(mark_id)?;
                self.base(base)?;
                for v in [t.a, t.b, t.c, t.d, t.tx, t.ty] {
                    self.raw(&v.to_bits().to_le_bytes());
                }
            }
            MarkMutation::RestyleText {
                mark_id,
                base,
                style,
            } => {
                self.tag(11);
                self.string(mark_id)?;
                self.base(base)?;
                self.text_style(style);
            }
        }
        Ok(())
    }
    fn image_placement(&mut self, placement: &ImagePlacement) -> CoreResult<()> {
        match placement {
            ImagePlacement::Canvas {
                sheet_id,
                x,
                y,
                width,
                height,
            } => {
                self.tag(0);
                self.string(sheet_id)?;
                for v in [x, y, width, height] {
                    self.raw(&v.to_bits().to_le_bytes());
                }
            }
            ImagePlacement::Text {
                anchor_id,
                width,
                height,
            } => {
                self.tag(1);
                self.string(anchor_id)?;
                for v in [width, height] {
                    self.raw(&v.to_bits().to_le_bytes());
                }
            }
        }
        Ok(())
    }
    fn image_mutation(&mut self, mutation: &ImageMutation) -> CoreResult<()> {
        match mutation {
            ImageMutation::Add {
                image_id,
                bytes,
                media_type,
                pixel_width,
                pixel_height,
                placement,
            } => {
                self.tag(0);
                self.string(image_id)?;
                self.blob(bytes)?;
                self.tag(match media_type {
                    ImageMediaType::Png => 0,
                    ImageMediaType::Jpeg => 1,
                });
                self.raw(&pixel_width.to_le_bytes());
                self.raw(&pixel_height.to_le_bytes());
                self.image_placement(placement)?;
            }
            ImageMutation::Delete { image_id, base }
            | ImageMutation::Restore { image_id, base }
            | ImageMutation::MarkSeen { image_id, base } => {
                self.tag(match mutation {
                    ImageMutation::Delete { .. } => 1,
                    ImageMutation::Restore { .. } => 2,
                    _ => 4,
                });
                self.string(image_id)?;
                self.base(base)?;
            }
            ImageMutation::Move {
                image_id,
                base,
                placement,
            } => {
                self.tag(3);
                self.string(image_id)?;
                self.base(base)?;
                self.image_placement(placement)?;
            }
        }
        Ok(())
    }
    fn finish(self) -> CoreResult<Vec<u8>> {
        if self.metadata > MAX_METADATA_BYTES {
            return Err(limit("Operation metadata exceeds 64 KiB"));
        }
        Ok(self.bytes)
    }
}
/// Creation tags. 0: before page formats (implies Inbox). 2: pre-canvas, with a page format
/// byte. 3: current. Only 3 is produced; 0 and 2 are matched for retries of old notes.
const LEGACY_CREATION: u8 = 0;
const FORMAT_CREATION: u8 = 2;
const CREATION: u8 = 3;
/// `format` is the tag-2 page format byte (Phone 0, Sheet16x10 1); other tags ignore it.
fn creation_with(
    request: &CreateNoteRequest,
    author: &str,
    tag: u8,
    format: u8,
) -> CoreResult<Vec<u8>> {
    let mut e = Encoder::new();
    e.tag(tag);
    for s in [
        &request.note_id,
        author,
        &request.operation_id,
        &request.first_sheet_id,
        &request.title,
    ] {
        e.string(s)?;
    }
    if tag == FORMAT_CREATION {
        e.tag(format);
    }
    if tag != LEGACY_CREATION {
        e.optional(request.notebook.as_deref())?;
    }
    e.finish()
}
/// Creation identity for new notes: tag 3, the five strings and the initial notebook.
/// A note is one canvas, so there is no page format.
pub(crate) fn creation(request: &CreateNoteRequest, author: &str) -> CoreResult<Vec<u8>> {
    creation_with(request, author, CREATION, 0)
}
/// Whether `request` is a canonical retry of the stored creation envelope. Stored
/// pre-canvas envelopes keep their meaning: tag 0 (before formats) implies Inbox and
/// matches only a retry without notebook; tag 2 matches a retry with the same strings
/// and notebook whatever format it recorded, since a request can no longer carry one.
pub(crate) fn creation_matches(
    stored: &[u8],
    request: &CreateNoteRequest,
    author: &str,
) -> CoreResult<bool> {
    Ok(match stored.get(PREFIX.len()) {
        Some(&LEGACY_CREATION) => {
            request.notebook.is_none()
                && creation_with(request, author, LEGACY_CREATION, 0)? == stored
        }
        Some(&FORMAT_CREATION) => {
            creation_with(request, author, FORMAT_CREATION, 0)? == stored
                || creation_with(request, author, FORMAT_CREATION, 1)? == stored
        }
        _ => creation(request, author)? == stored,
    })
}
/// A command's identity. Dependencies are sorted and deduplicated here, so their order
/// and repetition never change identity.
pub(crate) fn command(command: &Command, note_id: &str, author: &str) -> CoreResult<Vec<u8>> {
    let mut e = Encoder::new();
    e.tag(1);
    for s in [note_id, author, &command.operation_id] {
        e.string(s)?;
    }
    let mut dependencies = command.dependencies.iter().collect::<Vec<_>>();
    dependencies.sort();
    dependencies.dedup();
    e.length(dependencies.len())?;
    for d in dependencies {
        e.string(d)?;
    }
    match &command.body {
        CommandBody::ImageMarks { mutations } => {
            e.tag(10);
            e.length(mutations.len())?;
            for mutation in mutations {
                e.image_mutation(mutation)?;
            }
        }
        CommandBody::CompanionMarks { mutations } => {
            e.tag(8);
            e.length(mutations.len())?;
            for m in mutations {
                e.string(&m.id)?;
                e.base(&m.base)?;
                e.tag(u8::from(m.tombstone));
                let json = serde_json::to_string(&m.body)
                    .map_err(|_| crate::error::invalid("Invalid companion body"))?;
                e.text(&json)?;
            }
        }
        CommandBody::SetCompanionSettings { settings, base } => {
            e.tag(9);
            e.base(base)?;
            let json = serde_json::to_string(settings)
                .map_err(|_| crate::error::invalid("Invalid companion settings"))?;
            e.text(&json)?;
        }
        CommandBody::Marks { mutations } => {
            e.tag(0);
            e.length(mutations.len())?;
            for m in mutations {
                e.mutation(m)?;
            }
        }
        CommandBody::AddSheet { sheet_id, base } => {
            e.tag(1);
            e.string(sheet_id)?;
            e.base(base)?;
        }
        CommandBody::ReorderSheets { sheet_ids, base } => {
            e.tag(2);
            e.length(sheet_ids.len())?;
            for s in sheet_ids {
                e.string(s)?;
            }
            e.base(base)?;
        }
        CommandBody::RenameNote { title, base } => {
            e.tag(3);
            e.string(title)?;
            e.base(base)?;
        }
        CommandBody::SetNotebook { notebook, base } => {
            e.tag(6);
            e.optional(notebook.as_deref())?;
            e.base(base)?;
        }
        CommandBody::SetArchived { archived, base } => {
            e.tag(7);
            e.tag(u8::from(*archived));
            e.base(base)?;
        }
        CommandBody::Undo {
            target_operation_id,
        } => {
            e.tag(4);
            e.string(target_operation_id)?;
        }
        CommandBody::Redo {
            target_operation_id,
        } => {
            e.tag(5);
            e.string(target_operation_id)?;
        }
    }
    e.finish()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reviewed_canonical_golden_and_dependency_normalization() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/offline-v1");
        let input: serde_json::Value =
            serde_json::from_slice(&std::fs::read(root.join("canonical.input.json")).unwrap())
                .unwrap();
        let expected: serde_json::Value =
            serde_json::from_slice(&std::fs::read(root.join("canonical.expected.json")).unwrap())
                .unwrap();
        let mut c: Command = serde_json::from_value(input["command"].clone()).unwrap();
        let note = input["note_id"].as_str().unwrap();
        let author = input["author_id"].as_str().unwrap();
        let bytes = command(&c, note, author).unwrap();
        let hex: String = bytes.iter().map(|v| format!("{v:02x}")).collect();
        assert_eq!(hex, expected["canonical_hex"]);
        assert_eq!(bytes.len(), expected["canonical_length"]);
        c.dependencies.sort();
        c.dependencies.dedup();
        assert_eq!(command(&c, note, author).unwrap(), bytes);
    }
    #[test]
    fn creation_v3_golden_and_pre_canvas_retries() {
        let id = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        let mut r = CreateNoteRequest {
            operation_id: id.into(),
            note_id: id.into(),
            first_sheet_id: id.into(),
            title: "T".into(),
            notebook: Some("P".into()),
        };
        let ids = format!("1a000000{}", hex(id.as_bytes())).repeat(4);
        let tail = "0100000054"; // title "T"
        let legacy = format!("53594e4f0100{}{}{}", "00", ids, tail);
        // Tag 3: no page format byte, then Some("P").
        let current = format!("53594e4f0100{}{}{}010100000050", "03", ids, tail);
        assert_eq!(hex(&creation(&r, id).unwrap()), current);
        // Pre-canvas tag 2 envelopes as the previous core stored them.
        let phone = format!("53594e4f0100{}{}{}00010100000050", "02", ids, tail);
        let sheet = format!("53594e4f0100{}{}{}01010100000050", "02", ids, tail);
        for stored in [&phone, &sheet] {
            let stored = unhex(stored);
            assert!(creation_matches(&stored, &r, id).unwrap());
            for changed in [
                CreateNoteRequest {
                    notebook: None,
                    ..r.clone()
                },
                CreateNoteRequest {
                    title: "U".into(),
                    ..r.clone()
                },
            ] {
                assert!(!creation_matches(&stored, &changed, id).unwrap());
            }
            // A tag-3 envelope never equals a tag-2 one.
            assert_ne!(creation(&r, id).unwrap(), stored);
        }
        r.notebook = None;
        // Inbox: title then optional-None, no format byte.
        assert_eq!(
            hex(&creation(&r, id).unwrap()),
            format!("53594e4f0100{}{}{}00", "03", ids, tail)
        );
        let stored = creation_with(&r, id, LEGACY_CREATION, 0).unwrap();
        assert_eq!(hex(&stored), legacy);
        // A pre-format note's retry (Inbox) still matches; a notebook or title change does not.
        assert!(creation_matches(&stored, &r, id).unwrap());
        for changed in [
            CreateNoteRequest {
                notebook: Some("P".into()),
                ..r.clone()
            },
            CreateNoteRequest {
                title: "U".into(),
                ..r.clone()
            },
        ] {
            assert!(!creation_matches(&stored, &changed, id).unwrap());
        }
        assert!(creation_matches(&creation(&r, id).unwrap(), &r, id).unwrap());
        let c = Command {
            operation_id: id.into(),
            dependencies: vec![],
            body: CommandBody::SetNotebook {
                notebook: Some("P".into()),
                base: Base::Absent,
            },
        };
        // Body tag 6, Some("P"), Base::Absent.
        assert!(hex(&command(&c, id, id).unwrap()).ends_with("000000000601010000005000"));
        let none = Command {
            body: CommandBody::SetNotebook {
                notebook: None,
                base: Base::Absent,
            },
            ..c
        };
        // Zero dependencies, body tag 6, None, Base::Absent.
        assert!(hex(&command(&none, id, id).unwrap()).ends_with("00000000060000"));
        let archive = Command {
            body: CommandBody::SetArchived {
                archived: true,
                base: Base::Absent,
            },
            ..none.clone()
        };
        // Zero dependencies, body tag 7, archived=1, Base::Absent.
        assert!(hex(&command(&archive, id, id).unwrap()).ends_with("00000000070100"));
        let unarchive = Command {
            body: CommandBody::SetArchived {
                archived: false,
                base: Base::Operation {
                    operation_id: id.into(),
                },
            },
            ..none
        };
        // Body tag 7, archived=0, Base::Operation(id).
        assert!(
            hex(&command(&unarchive, id, id).unwrap())
                .ends_with(&format!("0700011a000000{}", hex(id.as_bytes())))
        );
    }
    fn unhex(value: &str) -> Vec<u8> {
        (0..value.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&value[i..i + 2], 16).unwrap())
            .collect()
    }
    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|v| format!("{v:02x}")).collect()
    }
    #[test]
    fn numeric_bits_sample_bytes_and_mutation_order_are_identity() {
        let id = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        let transform = Affine {
            a: 1.0,
            b: 0.0,
            c: 0.0,
            d: 1.0,
            tx: -0.0,
            ty: 0.0,
        };
        let mut c = Command {
            operation_id: id.into(),
            dependencies: vec![],
            body: CommandBody::Marks {
                mutations: vec![MarkMutation::TransformStroke {
                    mark_id: id.into(),
                    base: Base::Absent,
                    transform,
                }],
            },
        };
        let bytes = command(&c, id, id).unwrap();
        assert_eq!(
            &bytes[bytes.len() - 16..bytes.len() - 8],
            &(-0.0f64).to_bits().to_le_bytes()
        );
        if let CommandBody::Marks { mutations } = &mut c.body
            && let MarkMutation::TransformStroke { transform, .. } = &mut mutations[0]
        {
            transform.tx = 0.0;
        }
        assert_ne!(command(&c, id, id).unwrap(), bytes);
        let m = MarkMutation::AddStroke {
            mark_id: id.into(),
            sheet_id: id.into(),
            samples: vec![1, 2, 3],
            brush: Brush {
                family: BrushFamily::Highlighter,
                size: BrushSize::Large,
                colour: InkColour::Orange,
            },
        };
        let n = MarkMutation::DeleteStroke {
            mark_id: id.into(),
            base: Base::Absent,
        };
        c.body = CommandBody::Marks {
            mutations: vec![m.clone(), n.clone()],
        };
        let first = command(&c, id, id).unwrap();
        c.body = CommandBody::Marks {
            mutations: vec![n, m],
        };
        assert_ne!(command(&c, id, id).unwrap(), first);
    }
}
