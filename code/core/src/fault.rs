//! Test-only fault injection. Both hooks compile to no-ops outside `cfg(test)`, so no
//! production build has an environment-controlled failure switch.

/// Crash point: a test child process started with `SYNCR_CRASH_STAGE=<stage>` exits with
/// code 91 here, which models the process dying at exactly this step.
#[cfg(test)]
pub(crate) fn crash_point(stage: &str) {
    if std::env::var("SYNCR_CRASH_STAGE").ok().as_deref() == Some(stage) {
        std::process::exit(91);
    }
}
#[cfg(not(test))]
pub(crate) fn crash_point(_: &str) {}

#[cfg(test)]
thread_local! {
    /// The stage at which [`fail_point`] returns an error on this thread.
    pub(crate) static FAIL_AT: std::cell::Cell<Option<&'static str>> =
        const { std::cell::Cell::new(None) };
}

/// In-process failure: returns `Internal` when [`FAIL_AT`] names this stage.
#[cfg(test)]
pub(crate) fn fail_point(stage: &str) -> crate::CoreResult<()> {
    if FAIL_AT.with(|f| f.get()) == Some(stage) {
        return Err(crate::error::internal());
    }
    Ok(())
}
#[cfg(not(test))]
pub(crate) fn fail_point(_: &str) -> crate::CoreResult<()> {
    Ok(())
}
