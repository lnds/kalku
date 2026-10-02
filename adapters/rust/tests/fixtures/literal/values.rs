pub const LIMIT: usize = 3;

pub fn numbers() -> (u32, u8, i64) {
    (0, 41u8 as u8, 100)
}

pub fn flags() -> (bool, bool) {
    (true, false)
}

pub fn text() -> (&'static str, &'static str) {
    ("hello", "")
}

// Another notation, or past the top of the type: not one defect alone.
pub fn untouched() -> (u32, u32, u32, u8, i8) {
    (0x10, 1_000, 0b11, 255u8, 127i8)
}

// `[u8; 4]` is a type and the 4 is structure; the element is a value.
pub fn array() -> [u8; 4] {
    [0u8; 4]
}

// A literal in a pattern is structure.
pub fn pattern(n: i32) -> bool {
    match n {
        0 => true,
        1 | 2 => false,
        _ => true,
    }
}

// Tokens inside a macro are not parsed, so they are not nodes.
pub fn macros() {
    println!("hello {}", 1 + 2);
}
