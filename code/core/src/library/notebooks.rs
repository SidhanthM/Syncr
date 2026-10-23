//! Flat notebooks. Membership is each note's own metadata (`SetNotebook`) and travels with
//! the file. The `notebooks` table only registers names, so empty notebooks can exist.
use super::{SyncrLibrary, lock};
use crate::{error::invalid, error::storage, validation, *};
use std::{collections::BTreeMap, sync::Arc};

pub(super) fn list_notebooks(library: &SyncrLibrary) -> CoreResult<Vec<NotebookSummary>> {
    let mut catalog = lock(&library.catalog)?;
    let mut counts = BTreeMap::<String, u64>::new();
    for p in library.scan(&mut catalog)? {
        if let Some(name) = p.state.notebook {
            *counts.entry(name).or_default() += 1;
        }
    }
    let mut statement = catalog
        .db
        .prepare("SELECT name FROM notebooks")
        .map_err(storage)?;
    let names = statement
        .query_map([], |r| r.get::<_, String>(0))
        .map_err(storage)?;
    for name in names {
        counts.entry(name.map_err(storage)?).or_default();
    }
    let mut result: Vec<_> = counts
        .into_iter()
        .map(|(name, note_count)| NotebookSummary { name, note_count })
        .collect();
    // Exact name breaks ties between names differing only in case.
    result.sort_by(|a, b| (a.name.to_lowercase(), &a.name).cmp(&(b.name.to_lowercase(), &b.name)));
    Ok(result)
}

pub(super) fn create_notebook(library: &SyncrLibrary, name: String) -> CoreResult<()> {
    validation::notebook_name(&name)?;
    lock(&library.catalog)?
        .db
        .execute("INSERT OR IGNORE INTO notebooks VALUES (?1)", [&name])
        .map_err(storage)?;
    Ok(())
}

pub(super) fn delete_notebook(library: &SyncrLibrary, name: String) -> CoreResult<()> {
    validation::notebook_name(&name)?;
    let mut catalog = lock(&library.catalog)?;
    if library
        .scan(&mut catalog)?
        .iter()
        .any(|p| p.state.notebook.as_deref() == Some(name.as_str()))
    {
        return Err(invalid("Notebook still contains notes"));
    }
    catalog
        .db
        .execute("DELETE FROM notebooks WHERE name=?1", [&name])
        .map_err(storage)?;
    Ok(())
}

pub(super) fn rename_notebook(
    library: &Arc<SyncrLibrary>,
    old_name: String,
    new_name: String,
) -> CoreResult<()> {
    validation::notebook_name(&old_name)?;
    validation::notebook_name(&new_name)?;
    if old_name == new_name {
        return Ok(());
    }
    let members: Vec<Id> = {
        let mut catalog = lock(&library.catalog)?;
        let tx = catalog.db.transaction().map_err(storage)?;
        let registered = tx
            .execute("DELETE FROM notebooks WHERE name=?1", [&old_name])
            .map_err(storage)?;
        if registered > 0 {
            tx.execute("INSERT OR IGNORE INTO notebooks VALUES (?1)", [&new_name])
                .map_err(storage)?;
        }
        tx.commit().map_err(storage)?;
        library
            .scan(&mut catalog)?
            .into_iter()
            .filter(|p| p.state.notebook.as_deref() == Some(old_name.as_str()))
            .map(|p| p.state.note_id)
            .collect()
    };
    // The catalog lock is released: open_note and submit take their own locks.
    for note_id in members {
        // open_note shares an already-open live writer, so this serializes with
        // the app's own submissions to the same note.
        let note = library.open_note(note_id)?;
        let snapshot = note.snapshot()?;
        if snapshot.notebook.as_deref() != Some(old_name.as_str()) {
            continue;
        }
        let head = snapshot.metadata_head.operation_id;
        note.submit(Command {
            operation_id: new_id(),
            dependencies: vec![head.clone()],
            body: CommandBody::SetNotebook {
                notebook: Some(new_name.clone()),
                base: Base::Operation { operation_id: head },
            },
        })?;
    }
    Ok(())
}
