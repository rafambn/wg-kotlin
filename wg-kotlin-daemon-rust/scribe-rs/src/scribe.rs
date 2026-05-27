use crate::margin::Margin;
use crate::saver::Saver;
use crate::scroll::{new_scroll_id, Scroll, SealedScroll};
use serde_json::{Map, Value};
use std::sync::Arc;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

pub struct Scribe {
    imprint: Map<String, Value>,
    margin: Option<Arc<dyn Margin>>,
    savers: Vec<Arc<dyn Saver>>,
    sender: Option<mpsc::UnboundedSender<SealedScroll>>,
    handle: Option<JoinHandle<()>>,
}

impl Scribe {
    pub fn builder() -> ScribeBuilder {
        ScribeBuilder::default()
    }

    pub fn hire(&mut self) {
        assert!(
            self.sender.is_none(),
            "Scribe already hired. Call retire() first."
        );

        let (sender, mut receiver) = mpsc::unbounded_channel::<SealedScroll>();
        let savers = self.savers.clone();

        let handle = tokio::spawn(async move {
            while let Some(scroll) = receiver.recv().await {
                for saver in &savers {
                    saver.save(&scroll);
                }
            }
        });

        self.sender = Some(sender);
        self.handle = Some(handle);
    }

    pub fn new_scroll(&self, id: Option<String>) -> Scroll {
        let mut scroll = Map::new();
        scroll.insert(
            "scroll_id".to_string(),
            Value::String(id.unwrap_or_else(new_scroll_id)),
        );

        for (key, value) in &self.imprint {
            scroll.insert(key.clone(), value.clone());
        }

        if let Some(margin) = &self.margin {
            margin.header(&mut scroll);
        }

        scroll
    }

    pub fn seal(&self, mut scroll: Scroll, success: bool) -> SealedScroll {
        if let Some(margin) = &self.margin {
            margin.footer(&mut scroll);
        }

        let sealed = SealedScroll {
            success,
            data: scroll,
        };
        self.enqueue(sealed.clone());
        sealed
    }

    pub async fn retire(&mut self) {
        if let Some(sender) = self.sender.take() {
            drop(sender);
        }

        if let Some(handle) = self.handle.take() {
            let _ = handle.await;
        }
    }

    fn enqueue(&self, scroll: SealedScroll) {
        match &self.sender {
            Some(sender) => {
                let _ = sender.send(scroll);
            }
            None => panic!("Scribe runtime is not active. Call hire() first."),
        }
    }
}

#[derive(Default)]
pub struct ScribeBuilder {
    imprint: Map<String, Value>,
    margin: Option<Arc<dyn Margin>>,
    savers: Vec<Arc<dyn Saver>>,
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

    pub fn build(self) -> Scribe {
        Scribe {
            imprint: self.imprint,
            margin: self.margin,
            savers: self.savers,
            sender: None,
            handle: None,
        }
    }
}
