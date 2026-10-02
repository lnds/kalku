pub fn plain(a: i32) -> i32 {
    if a > 3 { 1 } else { 0 }
}

pub fn compound(a: i32) -> i32 {
    if a > 3 && a < 9 { 1 } else { 0 }
}

// `if let` binds, and there is no negation of a binding.
pub fn binding(a: Option<i32>) -> i32 {
    if let Some(x) = a { x } else { 0 }
}

pub fn removed(done: bool) -> bool {
    !done
}

pub fn elsewhere(a: i32) -> i32 {
    if a == 0 {
        0
    } else if a == 1 {
        1
    } else {
        2
    }
}
