//! A panic hook that records what a panic was, never what it was about (link part K.6). On 2026-11-16 a physical phone
//! panicked under delta sync and the library lock was poisoned; nothing was kept that could say where. The hook writes one
//! line per panic to logcat (tag `SyncrCore`, Android only) and to a small file under the library root, and then runs the
//! hook that was there before. A line holds the thread's name, the source location, the message cut to printable ASCII
//! and a short backtrace (function names only). No note content is read: a message is the programmer's text, trimmed.
use std::{
    backtrace::Backtrace,
    fs::{self, OpenOptions},
    io::Write,
    panic::{self, PanicHookInfo},
    path::{Path, PathBuf},
    sync::{Mutex, Once},
};

/// Where panics are recorded; the library root most recently opened.
const FILE_NAME: &str = ".core-panic.log";
/// The file never grows past this: a longer one is cut to its newest half before the next line is written.
const MAX_FILE_BYTES: u64 = 64 * 1024;
const MAX_MESSAGE: usize = 240;
const MAX_FRAMES: usize = 24;

static HOOK: Once = Once::new();
static TARGET: Mutex<Option<PathBuf>> = Mutex::new(None);

/// Installs the hook once per process and points it at `root`. Later calls only move the file.
pub(crate) fn install(root: &Path) {
    if let Ok(mut target) = TARGET.lock() {
        *target = Some(root.join(FILE_NAME));
    }
    HOOK.call_once(|| {
        let previous = panic::take_hook();
        panic::set_hook(Box::new(move |info| {
            record(info);
            previous(info);
        }));
    });
}

fn record(info: &PanicHookInfo<'_>) {
    let message = info
        .payload()
        .downcast_ref::<&str>()
        .map(|s| (*s).to_owned())
        .or_else(|| info.payload().downcast_ref::<String>().cloned())
        .unwrap_or_default();
    let place = info
        .location()
        .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
        .unwrap_or_else(|| "unknown".into());
    let thread = std::thread::current()
        .name()
        .unwrap_or("unnamed")
        .to_owned();
    let frames = frames(&Backtrace::force_capture().to_string());
    let line = format_line(&thread, &place, &message, &frames);
    android_log(&line);
    if let Ok(target) = TARGET.lock()
        && let Some(path) = target.as_ref()
    {
        append_bounded(path, &line);
    }
}

/// The one line a panic leaves: nothing but code-side text.
pub(crate) fn format_line(thread: &str, place: &str, message: &str, frames: &[String]) -> String {
    format!(
        "panic thread={} at={} message=\"{}\" frames={}",
        clean(thread, 40),
        clean(place, 160),
        clean(message, MAX_MESSAGE),
        frames.join(" < ")
    )
}

/// Printable ASCII only, cut to `max`: whatever the message carried, it leaves no free text beyond that.
fn clean(text: &str, max: usize) -> String {
    text.chars()
        .map(|c| {
            if c == '"' || c == '\n' || c == '\r' || !(' '..='~').contains(&c) {
                '?'
            } else {
                c
            }
        })
        .take(max)
        .collect()
}

/// Function names from a backtrace's text, innermost first, the standard library's panic machinery skipped.
fn frames(backtrace: &str) -> Vec<String> {
    backtrace
        .lines()
        .filter_map(|l| {
            let l = l.trim();
            let (index, name) = l.split_once(": ")?;
            index.parse::<u32>().ok()?;
            Some(clean(name, 90))
        })
        .filter(|n| {
            !n.contains("std::panicking")
                && !n.contains("core::panicking")
                && !n.contains("panic_log")
        })
        .take(MAX_FRAMES)
        .collect()
}

/// Appends `line` to `path`, first cutting a file past [`MAX_FILE_BYTES`] to its newest half. Errors are ignored: a
/// panic hook must not panic.
pub(crate) fn append_bounded(path: &Path, line: &str) {
    if let Ok(meta) = fs::metadata(path)
        && meta.len() > MAX_FILE_BYTES
        && let Ok(old) = fs::read(path)
    {
        let keep = &old[old.len() / 2..];
        let start = keep.iter().position(|b| *b == b'\n').map_or(0, |i| i + 1);
        let _ = fs::write(path, &keep[start..]);
    }
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(file, "{line}");
    }
}

#[cfg(target_os = "android")]
fn android_log(line: &str) {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};
    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }
    const ANDROID_LOG_ERROR: c_int = 6;
    if let (Ok(tag), Ok(text)) = (CString::new("SyncrCore"), CString::new(line)) {
        unsafe { __android_log_write(ANDROID_LOG_ERROR, tag.as_ptr(), text.as_ptr()) };
    }
}

#[cfg(not(target_os = "android"))]
fn android_log(_: &str) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_line_names_the_place_and_carries_no_free_text() {
        let line = format_line(
            "syncr-link-io",
            "src/library/sync.rs:10:5",
            "bad \"note\" text\nwith a newline and caf\u{e9}",
            &["a::b".into(), "c::d".into()],
        );
        assert!(line.starts_with("panic thread=syncr-link-io at=src/library/sync.rs:10:5 "));
        assert!(!line.contains('\n') && line.is_ascii());
        assert!(line.contains("bad ?note? text?with a newline and caf?"));
        assert!(line.ends_with("frames=a::b < c::d"));
        let long = format_line("t", "p", &"x".repeat(5_000), &[]);
        assert!(long.len() < 400);
    }

    #[test]
    fn the_file_stays_bounded_and_keeps_the_newest_lines() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join(FILE_NAME);
        let line = "x".repeat(990);
        for i in 0..200 {
            append_bounded(&path, &format!("{i:03} {line}"));
        }
        let size = fs::metadata(&path).unwrap().len();
        assert!(size <= MAX_FILE_BYTES + 1_100, "{size}");
        let text = fs::read_to_string(&path).unwrap();
        assert!(text.lines().last().unwrap().starts_with("199 "));
        assert!(!text.contains("000 x"));
        assert!(text.lines().all(|l| l.len() == 994), "no torn lines");
    }

    #[test]
    fn a_real_panic_is_recorded_in_the_library_folder_and_still_unwinds() {
        let dir = tempfile::tempdir().unwrap();
        install(dir.path());
        let result = std::thread::Builder::new()
            .name("panic-log-probe".into())
            .spawn(|| panic!("deliberate probe panic"))
            .unwrap()
            .join();
        assert!(result.is_err());
        // Another test may move the target meanwhile; the line is in whichever folder it points at, or this one.
        let target = TARGET.lock().unwrap().clone().unwrap();
        let text = fs::read_to_string(&target).unwrap_or_default();
        if target == dir.path().join(FILE_NAME) {
            assert!(text.contains("thread=panic-log-probe"), "{text}");
            assert!(text.contains("deliberate probe panic"));
        }
    }
}
