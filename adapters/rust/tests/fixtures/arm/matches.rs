pub fn with_a_wildcard(n: i32) -> u8 {
    match n {
        1 => 10,
        2 => 20,
        _ => 0,
    }
}

pub enum E {
    A,
    B,
}

// Exhaustive without a catch-all: deleting an arm would not compile.
pub fn exhaustive(e: E) -> u8 {
    match e {
        E::A => 1,
        E::B => 2,
    }
}

// `None` is an identifier pattern, but it is a variant, not a binding.
pub fn variants(o: Option<u8>) -> u8 {
    match o {
        Some(_) => 1,
        None => 0,
    }
}

pub fn lowercase_binding(o: Option<u8>) -> u8 {
    match o {
        Some(1) => 1,
        other => other.unwrap_or(0),
    }
}

pub fn guarded(n: i32) -> u8 {
    match n {
        1 => 1,
        _ if n > 5 => 2,
        _ => 0,
    }
}

pub fn blocks(n: i32) -> i32 {
    match n {
        0 => {
            let a = 1;
            a + 1
        }
        _ => 3,
    }
}
