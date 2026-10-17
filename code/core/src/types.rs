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
/// A note is one canvas, `width` note units wide, growing downward without pages.
/// `view_height` is the Mac's 16:10 window over the full width; `phone_column` is the
/// part of the width an upright phone shows at its default zoom.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct CanvasSize {
    pub width: f32,
    pub view_height: f32,
    pub phone_column: f32,
}
pub const CANVAS_WIDTH: f32 = 4800.0;
pub const CANVAS_VIEW_HEIGHT: f32 = 3000.0;
pub const CANVAS_PHONE_COLUMN: f32 = 1600.0;
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
    /// Fixed place of this sheet on the canvas. A stroke's canvas position is this origin
    /// plus its transform applied to its (sheet-local) samples. Sheets of notes written
    /// before the canvas amendment sit at (0, index x old page height), no gap; the first
    /// sheet, and the only sheet of a newer note, is at (0, 0).
    pub origin_x: f32,
    pub origin_y: f32,
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
    /// Wall-clock Unix ms of the deletion, recorded in the trash file name.
    pub deleted_at_ms: i64,
    pub created_at_ms: i64,
}
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct NoteSnapshot {
    pub note_id: Id,
    pub title: String,
    pub notebook: Option<String>,
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
    // Default only applies to internal JSON (fixtures, stored payloads), not the FFI.
    // Stored pre-canvas creation payloads also carry `page_format`; it is ignored here
    // (see reducer::LegacyPageFormat).
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
