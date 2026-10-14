//! The frozen offline-api.md boundary. IDs are canonical uppercase ULID strings.

pub type Id = String;

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum BrushFamily {
    Pen,
    Highlighter,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum BrushSize {
    Small,
    Medium,
    Large,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum InkColour {
    Ink,
    Red,
    Green,
    Blue,
    Orange,
}
/// Page shape of every sheet in a note, fixed at creation.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum PageFormat {
    /// Portrait phone page, 1600 x 3200 note units.
    Phone,
    /// Landscape 16:10 sheet, 1600 x 1000 note units (every note created before formats existed).
    Sheet16x10,
}
impl PageFormat {
    /// (width, height) in note units, the coordinate space of stroke samples.
    pub fn dimensions(self) -> (f32, f32) {
        match self {
            PageFormat::Phone => (1600.0, 3200.0),
            PageFormat::Sheet16x10 => (1600.0, 1000.0),
        }
    }
}
pub(crate) fn legacy_page_format() -> PageFormat {
    PageFormat::Sheet16x10
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PageSize {
    pub width: f32,
    pub height: f32,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct NotebookSummary {
    pub name: String,
    pub note_count: u64,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct Brush {
    pub family: BrushFamily,
    pub size: BrushSize,
    pub colour: InkColour,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct Affine {
    pub a: f64,
    pub b: f64,
    pub c: f64,
    pub d: f64,
    pub tx: f64,
    pub ty: f64,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum Base {
    Absent,
    Operation { operation_id: Id },
    Confirmed { revision: u64 },
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct EntityHead {
    pub operation_id: Id,
    pub confirmed_revision: Option<u64>,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct EventCursor {
    pub stream_id: Id,
    pub sequence: u64,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct SheetView {
    pub id: Id,
    pub creation_operation_id: Id,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct StrokeView {
    pub id: Id,
    pub sheet_id: Id,
    pub author_id: Id,
    pub head: EntityHead,
    pub transform: Affine,
    pub brush: Brush,
    pub tombstone: bool,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct NoteSummary {
    pub id: Id,
    pub title: String,
    pub local_sequence: u64,
    pub page_format: PageFormat,
    /// None = Inbox.
    pub notebook: Option<String>,
    /// Wall-clock display times (Unix ms). Not part of identity or idempotency.
    pub created_at_ms: i64,
    pub modified_at_ms: i64,
    /// Archived notes stay in the library but are hidden from normal views. Note metadata,
    /// like `notebook`. Trailing with a default so existing positional constructors compile.
    #[uniffi(default = false)]
    pub archived: bool,
}
/// A note in Recently deleted (`<root>/.trash/`). Not openable until restored.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct DeletedNoteSummary {
    pub id: Id,
    pub title: String,
    /// None = Inbox.
    pub notebook: Option<String>,
    pub page_format: PageFormat,
    /// Wall-clock Unix ms of the deletion, recorded in the trash file name.
    pub deleted_at_ms: i64,
    pub created_at_ms: i64,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct NoteSnapshot {
    pub note_id: Id,
    pub title: String,
    pub notebook: Option<String>,
    pub page_format: PageFormat,
    /// Page size in note units (the sample coordinate space); every sheet shares it.
    pub page_width: f32,
    pub page_height: f32,
    pub metadata_head: EntityHead,
    pub structure_head: EntityHead,
    pub sheets: Vec<SheetView>,
    pub strokes: Vec<StrokeView>,
    pub local_sequence: u64,
    pub cursor: EventCursor,
    pub undo_target: Option<Id>,
    pub redo_target: Option<Id>,
    pub undo_dependencies: Vec<Id>,
    pub redo_dependencies: Vec<Id>,
    /// Metadata, like `notebook`; changed by SetArchived. Trailing with a default.
    #[uniffi(default = false)]
    pub archived: bool,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct SampleChannels {
    pub x: Vec<f32>,
    pub y: Vec<f32>,
    pub pressure: Vec<f32>,
    pub elapsed_ms: Vec<u32>,
    pub tilt: Option<Vec<f32>>,
    pub orientation: Option<Vec<f32>>,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CreateNoteRequest {
    pub operation_id: Id,
    pub note_id: Id,
    pub first_sheet_id: Id,
    pub title: String,
    // Defaults only apply to internal JSON (fixtures, stored payloads), not the FFI.
    #[serde(default = "legacy_page_format")]
    pub page_format: PageFormat,
    #[serde(default)]
    pub notebook: Option<String>,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum MarkMutation {
    AddStroke {
        mark_id: Id,
        sheet_id: Id,
        samples: Vec<u8>,
        brush: Brush,
    },
    DeleteStroke {
        mark_id: Id,
        base: Base,
    },
    RestoreStroke {
        mark_id: Id,
        base: Base,
    },
    TransformStroke {
        mark_id: Id,
        base: Base,
        transform: Affine,
    },
    RestyleStroke {
        mark_id: Id,
        base: Base,
        brush: Brush,
    },
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum CommandBody {
    Marks {
        mutations: Vec<MarkMutation>,
    },
    AddSheet {
        sheet_id: Id,
        base: Base,
    },
    ReorderSheets {
        sheet_ids: Vec<Id>,
        base: Base,
    },
    RenameNote {
        title: String,
        base: Base,
    },
    /// Moves the note to a notebook (None = Inbox). Metadata head/base, like RenameNote.
    SetNotebook {
        notebook: Option<String>,
        base: Base,
    },
    /// Archives or unarchives the note. Metadata head/base, like SetNotebook; never ink undo.
    SetArchived {
        archived: bool,
        base: Base,
    },
    Undo {
        target_operation_id: Id,
    },
    Redo {
        target_operation_id: Id,
    },
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct Command {
    pub operation_id: Id,
    pub dependencies: Vec<Id>,
    pub body: CommandBody,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct Receipt {
    pub operation_id: Id,
    pub local_sequence: u64,
    pub cursor: EventCursor,
    pub duplicate: bool,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum OperationLookup {
    NotRecorded,
    Committed { receipt: Receipt },
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum ChangeKind {
    NoteCreated,
    MarksChanged,
    StructureChanged,
    MetadataChanged,
    HistoryChanged,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ChangeEvent {
    pub cursor: EventCursor,
    pub operation_id: Id,
    pub kind: ChangeKind,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum ChangePage {
    Events {
        items: Vec<ChangeEvent>,
        next_cursor: EventCursor,
        has_more: bool,
    },
    ResyncRequired,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ExportReceipt {
    pub note_id: Id,
    pub exported_local_sequence: u64,
}
