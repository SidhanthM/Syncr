//! SYNO v1 canonical operations (creation tags 0 legacy / 2 current). See README for the byte grammar. Never JSON equality.
use crate::{error::limit, *};

struct Encoder {
    bytes: Vec<u8>,
    metadata: usize,
    samples: usize,
}
impl Encoder {
    fn new() -> Self {
        Self {
            bytes: b"SYNO\x01\x00".to_vec(),
            metadata: 6,
            samples: 0,
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
        if value.len() > 64 * 1024
            || self.metadata.saturating_add(4).saturating_add(value.len()) > 64 * 1024
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
        if self.samples > crate::samples::MAX_SAMPLE_BYTES {
            return Err(limit("Gesture samples exceed 16 MiB"));
        }
        self.bytes.extend_from_slice(value);
        Ok(())
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
    fn finish(self) -> CoreResult<Vec<u8>> {
        if self.metadata > 64 * 1024 {
            return Err(limit("Operation metadata exceeds 64 KiB"));
        }
        Ok(self.bytes)
    }
}
const LEGACY_CREATION: u8 = 0;
const CREATION: u8 = 2;
fn creation_with(request: &CreateNoteRequest, author: &str, tag: u8) -> CoreResult<Vec<u8>> {
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
    if tag == CREATION {
        e.tag(match request.page_format {
            PageFormat::Phone => 0,
            PageFormat::Sheet16x10 => 1,
        });
        e.optional(request.notebook.as_deref())?;
    }
    e.finish()
}
/// Creation identity for new notes: tag 2 adds page format and initial notebook.
pub(crate) fn creation(request: &CreateNoteRequest, author: &str) -> CoreResult<Vec<u8>> {
    creation_with(request, author, CREATION)
}
/// Whether `request` is a canonical retry of the stored creation envelope. Notes
/// created before page formats store tag 0, which implies Sheet16x10 and Inbox.
pub(crate) fn creation_matches(
    stored: &[u8],
    request: &CreateNoteRequest,
    author: &str,
) -> CoreResult<bool> {
    if stored.get(6) == Some(&LEGACY_CREATION) {
        if request.page_format != PageFormat::Sheet16x10 || request.notebook.is_some() {
            return Ok(false);
        }
        return Ok(creation_with(request, author, LEGACY_CREATION)? == stored);
    }
    Ok(creation(request, author)? == stored)
}
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
        CommandBody::Marks { mutations } => {
            e.tag(0);
            e.length(mutations.len())?;
            for m in mutations {
                match m {
                    MarkMutation::AddStroke {
                        mark_id,
                        sheet_id,
                        samples,
                        brush,
                    } => {
                        e.tag(0);
                        e.string(mark_id)?;
                        e.string(sheet_id)?;
                        e.blob(samples)?;
                        e.brush(brush);
                    }
                    MarkMutation::DeleteStroke { mark_id, base } => {
                        e.tag(1);
                        e.string(mark_id)?;
                        e.base(base)?;
                    }
                    MarkMutation::RestoreStroke { mark_id, base } => {
                        e.tag(2);
                        e.string(mark_id)?;
                        e.base(base)?;
                    }
                    MarkMutation::TransformStroke {
                        mark_id,
                        base,
                        transform,
                    } => {
                        e.tag(3);
                        e.string(mark_id)?;
                        e.base(base)?;
                        for v in [
                            transform.a,
                            transform.b,
                            transform.c,
                            transform.d,
                            transform.tx,
                            transform.ty,
                        ] {
                            e.raw(&v.to_bits().to_le_bytes());
                        }
                    }
                    MarkMutation::RestyleStroke {
                        mark_id,
                        base,
                        brush,
                    } => {
                        e.tag(4);
                        e.string(mark_id)?;
                        e.base(base)?;
                        e.brush(brush);
                    }
                }
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
    fn creation_v2_golden_and_legacy_retries() {
        let id = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        let mut r = CreateNoteRequest {
            operation_id: id.into(),
            note_id: id.into(),
            first_sheet_id: id.into(),
            title: "T".into(),
            page_format: PageFormat::Phone,
            notebook: Some("P".into()),
        };
        let ids = format!("1a000000{}", hex(id.as_bytes())).repeat(4);
        let tail = "0100000054"; // title "T"
        let legacy = format!("53594e4f0100{}{}{}", "00", ids, tail);
        let current = format!("53594e4f0100{}{}{}00010100000050", "02", ids, tail);
        assert_eq!(hex(&creation(&r, id).unwrap()), current);
        r.notebook = None;
        assert!(hex(&creation(&r, id).unwrap()).ends_with("0000"));
        r.page_format = PageFormat::Sheet16x10;
        assert!(hex(&creation(&r, id).unwrap()).ends_with("0100"));
        let stored = creation_with(&r, id, LEGACY_CREATION).unwrap();
        assert_eq!(hex(&stored), legacy);
        // A pre-format note's retry carries Sheet16x10 + Inbox and still matches.
        assert!(creation_matches(&stored, &r, id).unwrap());
        assert!(
            !creation_matches(
                &creation(&r, id).unwrap(),
                &{
                    let mut p = r.clone();
                    p.page_format = PageFormat::Phone;
                    p
                },
                id
            )
            .unwrap()
        );
        for changed in [
            CreateNoteRequest {
                page_format: PageFormat::Phone,
                ..r.clone()
            },
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
