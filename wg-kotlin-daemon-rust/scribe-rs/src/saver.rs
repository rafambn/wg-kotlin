use crate::scroll::SealedScroll;

pub trait Saver: Send + Sync {
    fn save(&self, scroll: &SealedScroll);
}
