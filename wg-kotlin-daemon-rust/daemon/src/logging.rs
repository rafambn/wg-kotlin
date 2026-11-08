use std::fmt::Display;
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::Path;
use std::sync::mpsc::{self, SyncSender};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Clone)]
pub struct Logger {
    sender: SyncSender<Message>,
}

enum Message {
    Line(String),
    Flush(mpsc::Sender<()>),
}

impl Logger {
    pub fn new(path: Option<&Path>) -> io::Result<Self> {
        let writer: Box<dyn Write + Send> = match path {
            Some(path) => {
                path.parent().filter(|parent| !parent.as_os_str().is_empty()).map_or(Ok(()), fs::create_dir_all)?;
                Box::new(OpenOptions::new().create(true).append(true).open(path)?)
            }
            None => Box::new(io::stdout()),
        };
        Self::with_writer(writer)
    }

    fn with_writer(mut writer: impl Write + Send + 'static) -> io::Result<Self> {
        let (sender, receiver) = mpsc::sync_channel(256);
        std::thread::Builder::new().name("daemon-logs".into()).spawn(move || {
            let _ = receiver.iter().try_for_each(|message| match message {
                Message::Line(line) => writeln!(writer, "{line}"),
                Message::Flush(done) => {
                    let _ = writer.flush();
                    let _ = done.send(());
                    Ok(())
                }
            });
        })?;
        Ok(Self { sender })
    }

    pub fn log(&self, message: impl Display) {
        let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
        let message = message.to_string().replace('\r', "\\r").replace('\n', "\\n");
        let _ = self.sender.try_send(Message::Line(format!("{timestamp} pid={} {message}", std::process::id())));
    }

    /// Best effort: logging must never hold up network cleanup or process exit.
    pub fn flush(&self) {
        let (done, completion) = mpsc::channel();
        let _ = self.sender.try_send(Message::Flush(done));
        let _ = completion.recv_timeout(Duration::from_millis(500));
    }
}

#[cfg(test)]
#[path = "../tests/unit/logging_tests.rs"]
mod tests;
