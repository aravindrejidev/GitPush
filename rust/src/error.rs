use std::fmt;

/// Error with a stable machine-readable `code` (consumed by the Kotlin UI)
/// and a human-readable `message`.
#[derive(Debug, Clone)]
pub struct Error {
    pub code: &'static str,
    pub message: String,
}

pub type Result<T> = std::result::Result<T, Error>;

impl Error {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Error {
            code,
            message: message.into(),
        }
    }

    pub fn cancelled() -> Self {
        Error::new("CANCELLED", "Operation cancelled")
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.code, self.message)
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::new("IO", e.to_string())
    }
}

impl From<serde_json::Error> for Error {
    fn from(e: serde_json::Error) -> Self {
        Error::new("JSON", e.to_string())
    }
}

impl From<zip::result::ZipError> for Error {
    fn from(e: zip::result::ZipError) -> Self {
        Error::new("ZIP", e.to_string())
    }
}

impl From<ignore::Error> for Error {
    fn from(e: ignore::Error) -> Self {
        Error::new("SCAN", e.to_string())
    }
}
