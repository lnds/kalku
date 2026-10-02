pub fn real(a: i32) -> bool {
    a > 1
}

// The code that ships outside tests is the code that is measured.
#[cfg(not(test))]
pub fn shipped(a: i32) -> bool {
    a > 2
}

pub unsafe fn raw(a: *const i32) -> bool {
    unsafe { *a > 3 }
}

/// Returns a >= b, or a == b.
// and a < b
pub fn documented(a: i32, b: i32) -> bool {
    a != b
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_works() {
        assert!(real(2) == true);
        assert!(1 < 2);
    }
}

#[test]
fn standalone() {
    assert!(3 > 2);
}
