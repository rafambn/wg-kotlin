use clap::{Args, ValueEnum};
use scribe_rs::{Margin, Saver, Scribe, ScribeBuilder, Scroll, SealedScroll};
use serde_json::Value;
use std::fs::{self, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, ValueEnum)]
pub enum LogTarget {
    File,
    Stdout,
    Stderr,
}

impl LogTarget {
    fn as_str(self) -> &'static str {
        match self {
            Self::File => "file",
            Self::Stdout => "stdout",
            Self::Stderr => "stderr",
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, PartialOrd, Ord, ValueEnum)]
pub enum LogLevel {
    Trace,
    Debug,
    #[default]
    Info,
    Warn,
    Error,
}

impl LogLevel {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Trace => "trace",
            Self::Debug => "debug",
            Self::Info => "info",
            Self::Warn => "warn",
            Self::Error => "error",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, ValueEnum)]
pub enum LogComponent {
    Daemon,
    Ipc,
    Session,
    Logging,
}

impl LogComponent {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Daemon => "daemon",
            Self::Ipc => "ipc",
            Self::Session => "session",
            Self::Logging => "logging",
        }
    }
}

#[derive(Args, Clone, Debug, Default)]
pub struct LogOptions {
    #[arg(long = "log-target", value_enum, value_delimiter = ',', default_value = "file")]
    pub targets: Vec<LogTarget>,
    #[arg(long, help = "Explicit path for the file log target")]
    pub log_path: Option<PathBuf>,
    #[arg(long = "log-level", value_enum, default_value = "info")]
    pub level: LogLevel,
    #[arg(long = "log-component", value_enum, value_delimiter = ',')]
    pub components: Vec<LogComponent>,
}

struct ProcessContext;

impl Margin for ProcessContext {
    fn header(&self, scroll: &mut Scroll) {
        let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64;
        scroll.insert("timestamp".into(), Value::from(timestamp));
        scroll.insert("component".into(), Value::from("daemon"));
        scroll.insert("level".into(), Value::from("info"));
    }
}

fn base_scribe() -> ScribeBuilder {
    Scribe::builder()
        .imprint("service", "wg-daemon")
        .imprint("version", env!("CARGO_PKG_VERSION"))
        .imprint("os", std::env::consts::OS)
        .imprint("arch", normalized_arch())
        .imprint("pid", std::process::id())
        .margin(Arc::new(ProcessContext))
}

struct JsonSaver {
    target: LogTarget,
    writer: Mutex<Box<dyn Write + Send>>,
}

impl JsonSaver {
    fn new(target: LogTarget, writer: impl Write + Send + 'static) -> Self {
        Self { target, writer: Mutex::new(Box::new(writer)) }
    }
}

impl Saver for JsonSaver {
    fn save(&self, scroll: &SealedScroll) {
        let result = (|| -> Result<(), String> {
            let mut json = serde_json::to_vec(scroll).map_err(|error| format!("serialize log: {error}"))?;
            json.push(b'\n');
            let mut writer = self.writer.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            writer.write_all(&json).and_then(|_| writer.flush()).map_err(|error| format!("write log: {error}"))
        })();
        if let Err(error) = result
            && self.target != LogTarget::Stderr
        {
            report_log_error(self.target.as_str(), &error, Some(scroll));
            // stderr is terminal: a failure there is never reported back
            // into this pipeline, so diagnostics cannot recurse.
            Self::new(LogTarget::Stderr, io::stderr()).save(scroll);
        }
    }
}

fn report_log_error(target: &str, error: &str, source: Option<&SealedScroll>) {
    let scribe = base_scribe().saver(Arc::new(JsonSaver::new(LogTarget::Stderr, io::stderr()))).build();
    let mut scroll = scribe.new_scroll(None);
    scroll.insert("event".into(), Value::from("daemon_log_error"));
    scroll.insert("component".into(), Value::from("logging"));
    scroll.insert("level".into(), Value::from("error"));
    scroll.insert("target".into(), Value::from(target));
    scroll.insert("error".into(), Value::from(error));
    if let Some(source) = source {
        for key in ["session_id", "event", "scroll_id"] {
            if let Some(value) = source.data.get(key) {
                let name = if key == "session_id" { key.to_string() } else { format!("source_{key}") };
                scroll.insert(name, value.clone());
            }
        }
    }
    scribe.seal(scroll, false);
}

pub fn create_daemon_scribe(options: &LogOptions) -> Scribe {
    let mut builder = base_scribe();
    let mut requested = if options.targets.is_empty() { vec![LogTarget::File] } else { options.targets.clone() };
    requested.sort_unstable();
    requested.dedup();
    let mut enabled = false;
    for target in requested {
        let saver = match target {
            LogTarget::File => {
                let result = (|| -> anyhow::Result<JsonSaver> {
                    let path = match &options.log_path {
                        Some(path) => path.clone(),
                        None => default_log_path()?,
                    };
                    if let Some(parent) = path.parent().filter(|parent| !parent.as_os_str().is_empty()) {
                        fs::create_dir_all(parent).map_err(|error| anyhow::anyhow!("create log directory {}: {error}", parent.display()))?;
                    }
                    let file =
                        OpenOptions::new().create(true).append(true).open(&path).map_err(|error| anyhow::anyhow!("{}: {error}", path.display()))?;
                    Ok(JsonSaver::new(target, BufWriter::new(file)))
                })();
                match result {
                    Ok(saver) => saver,
                    Err(error) => {
                        report_log_error("file", &error.to_string(), None);
                        continue;
                    }
                }
            }
            LogTarget::Stdout => JsonSaver::new(target, io::stdout()),
            LogTarget::Stderr => JsonSaver::new(target, io::stderr()),
        };
        builder = builder.saver(Arc::new(saver));
        enabled = true;
    }
    if !enabled {
        builder = builder.saver(Arc::new(JsonSaver::new(LogTarget::Stderr, io::stderr())));
    }
    let minimum = options.level;
    let components = options.components.clone();
    builder
        .filter(move |scroll| {
            let level = scroll.data.get("level").and_then(Value::as_str).and_then(|value| LogLevel::from_str(value, false).ok()).unwrap_or_default();
            let component = scroll.data.get("component").and_then(Value::as_str).unwrap_or("daemon");
            level >= minimum && (components.is_empty() || components.iter().any(|value| value.as_str() == component))
        })
        .build()
}

pub fn default_log_path() -> anyhow::Result<PathBuf> {
    #[cfg(target_os = "linux")]
    {
        Ok(PathBuf::from("/var/log/wg-kotlin/daemon.jsonl"))
    }
    #[cfg(target_os = "macos")]
    {
        Ok(PathBuf::from("/Library/Logs/wg-kotlin/daemon.jsonl"))
    }
    #[cfg(windows)]
    {
        use std::ffi::OsString;
        use std::os::windows::ffi::OsStringExt;
        use windows::Win32::System::Com::CoTaskMemFree;
        use windows::Win32::UI::Shell::{FOLDERID_ProgramData, KF_FLAG_DEFAULT, SHGetKnownFolderPath};
        let folder = unsafe { SHGetKnownFolderPath(&FOLDERID_ProgramData, KF_FLAG_DEFAULT, None)? };
        let path = PathBuf::from(OsString::from_wide(unsafe { folder.as_wide() }));
        unsafe { CoTaskMemFree(Some(folder.0.cast())) };
        Ok(path.join("wg-kotlin").join("logs").join("daemon.jsonl"))
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", windows)))]
    {
        anyhow::bail!("unsupported operating system for daemon logs")
    }
}

pub async fn retire_scribe(scribe: &tokio::sync::Mutex<Scribe>) {
    if tokio::time::timeout(Duration::from_millis(500), async { scribe.lock().await.retire().await }).await.is_err() {
        let (finished, completion) = tokio::sync::oneshot::channel();
        std::thread::spawn(move || {
            report_log_error("flush", "log worker did not finish within 500ms", None);
            let _ = finished.send(());
        });
        let _ = tokio::time::timeout(Duration::from_millis(100), completion).await;
    }
}

pub fn report_fatal_error(error: String) {
    let (finished, completion) = std::sync::mpsc::sync_channel(1);
    std::thread::spawn(move || {
        let scribe = base_scribe().saver(Arc::new(JsonSaver::new(LogTarget::Stderr, io::stderr()))).build();
        let mut scroll = scribe.new_scroll(None);
        scroll.insert("event".into(), Value::from("daemon_error"));
        scroll.insert("level".into(), Value::from("error"));
        scroll.insert("error".into(), Value::from(error));
        scribe.seal(scroll, false);
        let _ = finished.send(());
    });
    let _ = completion.recv_timeout(Duration::from_millis(100));
}

fn normalized_arch() -> &'static str {
    match std::env::consts::ARCH {
        "x86_64" => "x64",
        "aarch64" => "arm64",
        "x86" | "i386" | "i686" => "x86",
        _ => std::env::consts::ARCH,
    }
}

#[cfg(test)]
#[path = "logging_tests.rs"]
mod tests;
