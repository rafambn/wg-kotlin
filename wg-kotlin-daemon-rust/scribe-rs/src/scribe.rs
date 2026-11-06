use crate::margin::Margin;
use crate::saver::Saver;
use crate::scroll::{Scroll, SealedScroll, new_scroll_id};
use serde_json::{Map, Value};
use std::sync::Arc;
use tokio::sync::{mpsc, oneshot};

type ScrollFilter = Arc<dyn Fn(&SealedScroll) -> bool + Send + Sync>;

pub struct Scribe {
    imprint: Map<String, Value>,
    margin: Option<Arc<dyn Margin>>,
    savers: Vec<Arc<dyn Saver>>,
    filter: Option<ScrollFilter>,
    sender: Option<mpsc::UnboundedSender<SealedScroll>>,
    completion: Option<oneshot::Receiver<()>>,
    retired: bool,
}

impl Scribe {
    pub fn builder() -> ScribeBuilder {
        ScribeBuilder::default()
    }

    /// Start a dedicated Saver worker. Before hire(), delivery is synchronous.
    pub fn hire(&mut self) {
        assert!(self.sender.is_none(), "Scribe already hired. Call retire() first.");

        let (sender, mut receiver) = mpsc::unbounded_channel::<SealedScroll>();
        let (finished, completion) = oneshot::channel();
        let savers = self.savers.clone();

        // A stuck Saver must not occupy an executor thread or keep a Tokio
        // runtime alive after the caller stops waiting for logs to drain.
        std::thread::spawn(move || {
            while let Some(scroll) = receiver.blocking_recv() {
                for saver in &savers {
                    saver.save(&scroll);
                }
            }
            let _ = finished.send(());
        });

        self.sender = Some(sender);
        self.completion = Some(completion);
        self.retired = false;
    }

    pub fn new_scroll(&self, id: Option<String>) -> Scroll {
        let mut scroll = Map::new();
        scroll.insert("scroll_id".to_string(), Value::String(id.unwrap_or_else(new_scroll_id)));

        for (key, value) in &self.imprint {
            scroll.insert(key.clone(), value.clone());
        }

        if let Some(margin) = &self.margin {
            margin.header(&mut scroll);
        }

        scroll
    }

    /// Finalize and filter a Scroll, then deliver it to the configured Savers.
    /// After retire(), delivery stays disabled until the next hire().
    pub fn seal(&self, mut scroll: Scroll, success: bool) -> SealedScroll {
        if let Some(margin) = &self.margin {
            margin.footer(&mut scroll);
        }

        let sealed = SealedScroll { success, data: scroll };
        if self.filter.as_ref().is_none_or(|filter| filter(&sealed)) {
            self.deliver(&sealed);
        }
        sealed
    }

    pub async fn retire(&mut self) {
        self.retired = true;
        if let Some(sender) = self.sender.take() {
            drop(sender);
        }

        if let Some(completion) = self.completion.take() {
            let _ = completion.await;
        }
    }

    fn deliver(&self, scroll: &SealedScroll) {
        match &self.sender {
            Some(sender) => {
                let _ = sender.send(scroll.clone());
            }
            None if !self.retired => {
                // Without hire(), seal delivers synchronously. This also lets
                // terminal diagnostics use Scrolls without starting a worker.
                for saver in &self.savers {
                    saver.save(scroll);
                }
            }
            None => {}
        }
    }
}

#[derive(Default)]
pub struct ScribeBuilder {
    imprint: Map<String, Value>,
    margin: Option<Arc<dyn Margin>>,
    savers: Vec<Arc<dyn Saver>>,
    filter: Option<ScrollFilter>,
}

impl ScribeBuilder {
    pub fn imprint(mut self, key: &str, value: impl Into<Value>) -> Self {
        self.imprint.insert(key.to_string(), value.into());
        self
    }

    pub fn margin(mut self, margin: Arc<dyn Margin>) -> Self {
        self.margin = Some(margin);
        self
    }

    pub fn saver(mut self, saver: Arc<dyn Saver>) -> Self {
        self.savers.push(saver);
        self
    }

    pub fn filter(mut self, filter: impl Fn(&SealedScroll) -> bool + Send + Sync + 'static) -> Self {
        self.filter = Some(Arc::new(filter));
        self
    }

    pub fn build(self) -> Scribe {
        Scribe {
            imprint: self.imprint,
            margin: self.margin,
            savers: self.savers,
            filter: self.filter,
            sender: None,
            completion: None,
            retired: false,
        }
    }
}
