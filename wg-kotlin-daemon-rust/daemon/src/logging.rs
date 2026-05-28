use anyhow::Context;
use scribe_rs::{Saver, Scribe, SealedScroll};
use std::fs::OpenOptions;
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

pub struct FileSaver {
    path: PathBuf,
    writer: Mutex<BufWriter<std::fs::File>>,
}

impl FileSaver {
    pub fn new(path: impl AsRef<Path>) -> io::Result<Self> {
        let path = path.as_ref().to_path_buf();
        let file = OpenOptions::new().create(true).append(true).open(&path)?;
        Ok(Self { path, writer: Mutex::new(BufWriter::new(file)) })
    }
}

impl Saver for FileSaver {
    fn save(&self, scroll: &SealedScroll) {
        let json = match serde_json::to_string(scroll) {
            Ok(value) => value,
            Err(error) => {
                eprintln!("scribe-rs: failed to serialize scroll: {error}");
                return;
            }
        };

        let mut guard = self.writer.lock().unwrap_or_else(|poisoned| poisoned.into_inner());

        if let Err(error) = writeln!(guard, "{json}") {
            eprintln!("scribe-rs: failed to write to {}: {error}", self.path.display());
            return;
        }

        if let Err(error) = guard.flush() {
            eprintln!("scribe-rs: failed to flush writer {}: {error}", self.path.display());
        }
    }
}

pub fn create_daemon_scribe(log_path: &Path) -> anyhow::Result<Scribe> {
    let file_saver = FileSaver::new(log_path).with_context(|| format!("failed to open daemon log file: {}", log_path.display()))?;

    Ok(Scribe::builder()
        .imprint("service", "wg-daemon")
        .imprint("version", env!("CARGO_PKG_VERSION"))
        .imprint("os", normalized_os())
        .imprint("arch", normalized_arch())
        .saver(Arc::new(file_saver))
        .build())
}

fn normalized_os() -> &'static str {
    match std::env::consts::OS {
        "windows" => "windows",
        "macos" => "macos",
        "linux" => "linux",
        _ => std::env::consts::OS,
    }
}

fn normalized_arch() -> &'static str {
    match std::env::consts::ARCH {
        "x86_64" => "x64",
        "aarch64" => "arm64",
        "x86" | "i386" | "i686" => "x86",
        _ => std::env::consts::ARCH,
    }
}
