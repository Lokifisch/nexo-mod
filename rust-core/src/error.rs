//! One error type for the whole crate, because everything it can produce ends
//! up in the same place: a thread-local string the JVM reads back through
//! `NexoNative.nativeLastError()`. Nothing here is matched on programmatically
//! across the FFI boundary — Java only ever sees a sentinel return value plus
//! this message — so a rich error enum would buy nothing and cost every module
//! a conversion impl.

use std::fmt;

#[derive(Debug, Clone)]
pub struct Error(String);

impl Error {
    pub fn new(message: impl Into<String>) -> Self {
        Self(message.into())
    }

    /// A handle Java passed in that no registry knows. Almost always a
    /// use-after-close on the Java side, so the message says so rather than
    /// just echoing the number.
    pub fn bad_handle(handle: i64) -> Self {
        Self(format!(
            "handle {handle} is not live (already closed, never opened, or belongs to a different object type)"
        ))
    }

    pub fn message(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Self(format!("io: {e}"))
    }
}

impl From<jni::errors::Error> for Error {
    fn from(e: jni::errors::Error) -> Self {
        Self(format!("jni: {e}"))
    }
}

/// Storage failures (chat history, chunk history). Flattened to a string like
/// everything else: Java can't act on an `SQLITE_CORRUPT` differently than on a
/// `SQLITE_FULL` — both mean "this optional feature is off for now" — and the
/// detail that *is* useful lives in the message, which ends up in the log.
impl From<rusqlite::Error> for Error {
    fn from(e: rusqlite::Error) -> Self {
        Self(format!("sqlite: {e}"))
    }
}

pub type Result<T> = std::result::Result<T, Error>;

/// Turns whatever `catch_unwind` caught into something a human can read.
///
/// A panic payload is `Box<dyn Any>`, and in practice it is a `&str` for
/// `panic!("literal")` and a `String` for `panic!("{formatted}")` — anything
/// else (a custom payload, or a panic from a foreign crate that uses one) has
/// no printable form at all, hence the last arm. Losing that detail is
/// acceptable; letting the unwind cross into the JVM is not.
pub fn describe_panic(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&'static str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "<non-string panic payload>".to_string()
    }
}
