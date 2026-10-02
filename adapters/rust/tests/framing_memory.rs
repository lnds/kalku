//! A peer that sends and never ends a line must not make this process hold
//! what it sends. Nothing a caller sees tells a line that was dropped from
//! one that was buffered and then thrown away, so the memory is measured.

use kalku_rust::framing::{Line, read_line};
use std::alloc::{GlobalAlloc, Layout, System};
use std::io::{self, Read};
use std::sync::atomic::{AtomicUsize, Ordering};

struct Counting;

static LIVE: AtomicUsize = AtomicUsize::new(0);
static PEAK: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let now = LIVE.fetch_add(layout.size(), Ordering::SeqCst) + layout.size();
        PEAK.fetch_max(now, Ordering::SeqCst);
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        LIVE.fetch_sub(layout.size(), Ordering::SeqCst);
        unsafe { System.dealloc(ptr, layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        let now = LIVE.fetch_add(new_size, Ordering::SeqCst) + new_size - layout.size();
        LIVE.fetch_sub(layout.size(), Ordering::SeqCst);
        PEAK.fetch_max(now, Ordering::SeqCst);
        unsafe { System.realloc(ptr, layout, new_size) }
    }
}

#[global_allocator]
static ALLOCATOR: Counting = Counting;

#[test]
fn a_line_that_never_ends_is_not_held_while_it_is_dropped() {
    let endless = io::repeat(b'a')
        .take(64 * 1024 * 1024)
        .chain(&b"\nok\n"[..]);
    let mut reader = io::BufReader::with_capacity(8192, endless);
    let before = LIVE.load(Ordering::SeqCst);
    PEAK.store(before, Ordering::SeqCst);

    let first = read_line(&mut reader, 1024).unwrap();

    assert_eq!(first, Some(Line::TooLong));
    let held = PEAK.load(Ordering::SeqCst) - before;
    assert!(
        held < 1024 * 1024,
        "held {held} bytes of a line that was dropped"
    );
    assert_eq!(
        read_line(&mut reader, 1024).unwrap(),
        Some(Line::Text("ok".into()))
    );
}
