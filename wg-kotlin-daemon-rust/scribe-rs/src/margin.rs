use crate::scroll::Scroll;

pub trait Margin: Send + Sync {
    fn header(&self, _scroll: &mut Scroll) {}
    fn footer(&self, _scroll: &mut Scroll) {}
}
