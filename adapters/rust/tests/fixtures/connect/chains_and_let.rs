pub fn gate(a: bool, b: bool, c: bool) -> bool {
    a && b || c
}

// Edition 2024 let chains bind, and `||` cannot: no connect site here.
pub fn chained(a: bool, b: Option<i32>) -> bool {
    if a && let Some(x) = b {
        x > 0
    } else {
        false
    }
}

pub fn nested(a: bool, b: bool, c: bool) -> bool {
    (a || b) && c
}
