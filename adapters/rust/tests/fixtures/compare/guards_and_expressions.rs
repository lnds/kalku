pub fn window(n: i32) -> bool {
    n >= 0 && n < 10
}

pub fn classify(n: i32) -> &'static str {
    match n {
        x if x > 100 => "big",
        x if x == 0 => "zero",
        _ => "small",
    }
}

pub fn ordering(a: i32, b: i32) -> bool {
    a == b || a != b || a <= b
}

pub fn shifts(mut bits: u32) -> u32 {
    bits >>= 1;
    bits <<= 2;
    bits
}
