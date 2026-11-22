//! Stateless admission checks. The transactional reducer additionally checks entity
//! existence, exact heads, durable dependencies and sheet-creation provenance.
use crate::{
    error::{invalid, limit, missing_dependency},
    samples, *,
};

/// Most marks one gesture may touch.
const MAX_GESTURE_TARGETS: usize = 1024;
const MAX_NOTEBOOK_NAME_BYTES: usize = 100;
use std::collections::HashSet;

/// IDs are canonical ULID strings: 26 characters, uppercase, round-tripping exactly.
pub(crate) fn id(value: &str) -> CoreResult<()> {
    let canonical = value.len() == 26
        && value
            .parse::<ulid::Ulid>()
            .is_ok_and(|id| id.to_string() == value);
    if !canonical {
        return Err(invalid("ID must be a canonical uppercase ULID"));
    }
    Ok(())
}

/// Flat notebook names: stored exactly as given, so callers trim before submitting.
pub(crate) fn notebook_name(name: &str) -> CoreResult<()> {
    if name.is_empty() {
        return Err(invalid("Notebook name must not be empty"));
    }
    if name.trim() != name {
        return Err(invalid(
            "Notebook name must not start or end with whitespace",
        ));
    }
    if name.len() > MAX_NOTEBOOK_NAME_BYTES {
        return Err(invalid("Notebook name exceeds 100 bytes"));
    }
    if name
        .chars()
        .any(|c| c == '/' || c == '\\' || c.is_control())
    {
        return Err(invalid(
            "Notebook name must not contain slashes or control characters",
        ));
    }
    // A notebook maps to a Mac folder later; dot names are not usable folders.
    if name == "." || name == ".." {
        return Err(invalid("Notebook name is not a valid folder name"));
    }
    if name.to_lowercase() == "inbox" {
        return Err(invalid("Inbox is reserved; use no notebook for Inbox"));
    }
    Ok(())
}

pub(crate) fn affine(value: &Affine) -> CoreResult<()> {
    if [value.a, value.b, value.c, value.d, value.tx, value.ty]
        .iter()
        .any(|x| !x.is_finite())
    {
        return Err(invalid("Affine values must be finite"));
    }
    // Scale columns before computing the determinant to avoid overflow for large
    // finite transforms. This validation does not rewrite stored matrix values.
    let left = value.a.abs().max(value.b.abs());
    let right = value.c.abs().max(value.d.abs());
    if left == 0.0
        || right == 0.0
        || (value.a / left) * (value.d / right) == (value.b / left) * (value.c / right)
    {
        return Err(invalid("Affine transform must be nonsingular"));
    }
    Ok(())
}

/// Offline, a base is either absent or a pending operation that the command lists as a
/// dependency. The reducer later checks it against the entity's current head.
pub(crate) fn base(value: &Base, dependencies: &HashSet<&str>) -> CoreResult<()> {
    match value {
        Base::Absent => Ok(()),
        Base::Confirmed { .. } => Err(invalid("Confirmed bases are not valid for unadopted notes")),
        Base::Operation { operation_id } => {
            id(operation_id)?;
            if !dependencies.contains(operation_id.as_str()) {
                return Err(missing_dependency(
                    "Entity head operation must be included in dependencies",
                ));
            }
            Ok(())
        }
    }
}

pub(crate) fn command(command: &Command) -> CoreResult<()> {
    id(&command.operation_id)?;
    for dep in &command.dependencies {
        id(dep)?;
        if *dep == command.operation_id {
            return Err(invalid("An operation cannot depend on itself"));
        }
    }
    // Duplicates are legal and will be deduplicated/sorted by canonical encoding.
    let dependencies = command.dependencies.iter().map(String::as_str).collect();
    match &command.body {
        CommandBody::ImageMarks { mutations } => crate::image::validate(mutations, &dependencies)?,
        CommandBody::CompanionMarks { mutations } => {
            if mutations.is_empty() || mutations.len() > 1024 {
                return Err(limit("Companion batch requires 1 to 1024 items"));
            }
            let mut ids = HashSet::new();
            for m in mutations {
                id(&m.id)?;
                base(&m.base, &dependencies)?;
                crate::companion::validate(&m.body)?;
                if !ids.insert(&m.id) {
                    return Err(invalid("Companion targets must be unique"));
                }
            }
        }
        CommandBody::SetCompanionSettings { settings, base: b } => {
            base(b, &dependencies)?;
            crate::companion::settings(settings)?;
        }
        CommandBody::Marks { mutations } => {
            if mutations.is_empty() || mutations.len() > MAX_GESTURE_TARGETS {
                return Err(limit("A gesture requires 1 to 1024 unique targets"));
            }
            let mut targets = HashSet::new();
            let mut sample_bytes = 0usize;
            for mutation in mutations {
                let mark_id = match mutation {
                    MarkMutation::AddStroke {
                        mark_id,
                        sheet_id,
                        samples: blob,
                        brush,
                    } => {
                        id(sheet_id)?;
                        crate::brush::validate(brush)?;
                        sample_bytes = sample_bytes
                            .checked_add(blob.len())
                            .ok_or_else(|| limit("Gesture samples exceed size limit"))?;
                        if sample_bytes > samples::MAX_SAMPLE_BYTES {
                            return Err(limit("Gesture samples exceed 16 MiB"));
                        }
                        samples::decode(blob)?;
                        mark_id
                    }
                    MarkMutation::AddText {
                        mark_id,
                        sheet_id,
                        origin_x,
                        origin_y,
                        width,
                        text,
                        ..
                    } => {
                        id(sheet_id)?;
                        if !origin_x.is_finite() || !origin_y.is_finite() {
                            return Err(invalid("Text origin must be finite"));
                        }
                        crate::text::width(*width)?;
                        crate::text::content(text)?;
                        mark_id
                    }
                    MarkMutation::SpliceText {
                        mark_id,
                        base: b,
                        insert,
                        ..
                    } => {
                        base(b, &dependencies)?;
                        crate::text::content(insert)?;
                        mark_id
                    }
                    MarkMutation::SetTextWidth {
                        mark_id,
                        base: b,
                        width,
                    } => {
                        base(b, &dependencies)?;
                        crate::text::width(*width)?;
                        mark_id
                    }
                    MarkMutation::DeleteText { mark_id, base: b }
                    | MarkMutation::RestoreText { mark_id, base: b }
                    | MarkMutation::RestyleText {
                        mark_id, base: b, ..
                    }
                    | MarkMutation::DeleteStroke { mark_id, base: b }
                    | MarkMutation::RestoreStroke { mark_id, base: b } => {
                        base(b, &dependencies)?;
                        mark_id
                    }
                    MarkMutation::AddPage {
                        sheet_id,
                        after,
                        width,
                        height,
                    } => {
                        id(sheet_id)?;
                        if let Some(after) = after {
                            id(after)?;
                        }
                        crate::pages::side(*width)?;
                        crate::pages::side(*height)?;
                        sheet_id
                    }
                    MarkMutation::ResizePage {
                        sheet_id,
                        base: b,
                        width,
                        height,
                    } => {
                        base(b, &dependencies)?;
                        crate::pages::side(*width)?;
                        crate::pages::side(*height)?;
                        sheet_id
                    }
                    MarkMutation::MovePage {
                        sheet_id,
                        base: b,
                        after,
                    } => {
                        base(b, &dependencies)?;
                        if let Some(after) = after {
                            id(after)?;
                        }
                        sheet_id
                    }
                    MarkMutation::DeletePage { sheet_id, base: b }
                    | MarkMutation::RestorePage { sheet_id, base: b } => {
                        base(b, &dependencies)?;
                        sheet_id
                    }
                    MarkMutation::RestyleStroke {
                        mark_id,
                        base: b,
                        brush,
                    } => {
                        base(b, &dependencies)?;
                        crate::brush::validate(brush)?;
                        mark_id
                    }
                    MarkMutation::TransformText {
                        mark_id,
                        base: b,
                        transform,
                    }
                    | MarkMutation::TransformStroke {
                        mark_id,
                        base: b,
                        transform,
                    } => {
                        base(b, &dependencies)?;
                        affine(transform)?;
                        mark_id
                    }
                };
                id(mark_id)?;
                if !targets.insert(mark_id) {
                    return Err(invalid("Gesture targets must be unique"));
                }
            }
        }
        // Canvas amendment: sheet order fixes every origin, so it can never change again.
        // Already committed sheet commands still replay from the log and still answer
        // identical retries as duplicates (checked before admission).
        CommandBody::AddSheet { .. } | CommandBody::ReorderSheets { .. } => {
            return Err(crate::reducer::sheets_fixed());
        }
        CommandBody::RenameNote { base: b, .. } | CommandBody::SetArchived { base: b, .. } => {
            base(b, &dependencies)?
        }
        CommandBody::SetPageStyle {
            sheet_id,
            spacing,
            base: b,
            ..
        } => {
            base(b, &dependencies)?;
            if let Some(sheet_id) = sheet_id {
                id(sheet_id)?;
            }
            crate::pages::style_parts(*spacing)?;
        }
        CommandBody::SetCanvasBackground {
            spacing, base: b, ..
        } => {
            base(b, &dependencies)?;
            crate::pages::style_parts(*spacing)?;
        }
        CommandBody::SetWaypoints {
            waypoints, base: b, ..
        } => {
            base(b, &dependencies)?;
            crate::pages::waypoints(waypoints)?;
        }
        CommandBody::SetNotebook { notebook, base: b } => {
            base(b, &dependencies)?;
            if let Some(name) = notebook {
                notebook_name(name)?;
            }
        }
        CommandBody::Undo {
            target_operation_id,
        }
        | CommandBody::Redo {
            target_operation_id,
        } => id(target_operation_id)?,
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    const ID: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    const OP: &str = "01ARZ3NDEKTSV4RRFFQ69G5FAW";
    fn delete() -> MarkMutation {
        MarkMutation::DeleteStroke {
            mark_id: ID.into(),
            base: Base::Operation {
                operation_id: ID.into(),
            },
        }
    }
    fn marks(mutations: Vec<MarkMutation>, dependencies: Vec<Id>) -> Command {
        Command {
            operation_id: OP.into(),
            dependencies,
            body: CommandBody::Marks { mutations },
        }
    }
    #[test]
    fn canonical_ids_only() {
        assert!(id(ID).is_ok());
        for value in [
            ID.to_lowercase(),
            "81ARZ3NDEKTSV4RRFFQ69G5FAV".into(),
            "invalid".into(),
        ] {
            assert!(matches!(id(&value), Err(CoreError::InvalidInput { .. })));
        }
    }
    #[test]
    fn batch_unique_and_dependencies_explicit() {
        assert!(command(&marks(vec![delete()], vec![ID.into(), ID.into()])).is_ok());
        assert!(matches!(
            command(&marks(vec![delete()], vec![])),
            Err(CoreError::MissingDependency { .. })
        ));
        assert!(matches!(
            command(&marks(vec![delete(), delete()], vec![ID.into()])),
            Err(CoreError::InvalidInput { .. })
        ));
        assert!(matches!(
            command(&marks(vec![], vec![])),
            Err(CoreError::ResourceLimit { .. })
        ));
        assert!(matches!(
            command(&marks(vec![delete(); 1025], vec![ID.into()])),
            Err(CoreError::ResourceLimit { .. })
        ));
        assert!(matches!(
            command(&marks(vec![delete()], vec![OP.into()])),
            Err(CoreError::InvalidInput { .. })
        ));
    }
    #[test]
    fn affine_golden_conventions_and_nonfinite_values() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/offline-v1");
        let input: serde_json::Value =
            serde_json::from_slice(&std::fs::read(root.join("affine.input.json")).unwrap())
                .unwrap();
        let expected: serde_json::Value =
            serde_json::from_slice(&std::fs::read(root.join("affine.expected.json")).unwrap())
                .unwrap();
        for (input, expected) in input
            .as_array()
            .unwrap()
            .iter()
            .zip(expected.as_array().unwrap())
        {
            assert_eq!(input["name"], expected["name"]);
            let v: Vec<f64> = serde_json::from_value(input["affine"].clone()).unwrap();
            let transform = Affine {
                a: v[0],
                b: v[1],
                c: v[2],
                d: v[3],
                tx: v[4],
                ty: v[5],
            };
            if expected["error"] == "InvalidInput" {
                assert!(matches!(
                    affine(&transform),
                    Err(CoreError::InvalidInput { .. })
                ));
            } else {
                affine(&transform).unwrap();
                let p: Vec<f64> = serde_json::from_value(input["point"].clone()).unwrap();
                let result = vec![
                    transform.a * p[0] + transform.c * p[1] + transform.tx,
                    transform.b * p[0] + transform.d * p[1] + transform.ty,
                ];
                let expected: Vec<f64> = serde_json::from_value(expected["point"].clone()).unwrap();
                assert_eq!(result, expected);
            }
        }
        assert!(matches!(
            affine(&Affine {
                a: f64::NAN,
                b: 0.0,
                c: 0.0,
                d: 1.0,
                tx: 0.0,
                ty: 0.0
            }),
            Err(CoreError::InvalidInput { .. })
        ));
    }

    #[test]
    fn notebook_names() {
        for ok in ["Physics", "Journal 2026", "Ünïcødé ✏️", &"a".repeat(100)] {
            notebook_name(ok).unwrap();
        }
        for bad in [
            "".to_string(),
            " Physics".into(),
            "Physics ".into(),
            "a".repeat(101),
            "é".repeat(51),
            "A/B".into(),
            "A\\B".into(),
            "A\nB".into(),
            "A\u{7f}B".into(),
            "Inbox".into(),
            "INBOX".into(),
            "inbox".into(),
            ".".into(),
            "..".into(),
        ] {
            assert!(
                matches!(notebook_name(&bad), Err(CoreError::InvalidInput { .. })),
                "{bad:?}"
            );
        }
    }

    #[test]
    fn confirmed_bases_rejected_offline() {
        let mut mutation = delete();
        if let MarkMutation::DeleteStroke { base, .. } = &mut mutation {
            *base = Base::Confirmed { revision: u64::MAX };
        }
        assert!(matches!(
            command(&marks(vec![mutation], vec![])),
            Err(CoreError::InvalidInput { .. })
        ));
    }
}
