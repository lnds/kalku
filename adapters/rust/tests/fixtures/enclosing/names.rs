pub const LIMIT: bool = 1 > 0;

pub static ENABLED: bool = true;

pub fn free(a: i32) -> bool {
    a > 2
}

pub mod parser {
    pub struct Parser;

    impl Parser {
        pub fn next(&self, a: i32) -> bool {
            a > 1
        }

        pub fn again(&self, a: i32) -> bool {
            a > 1 && a > 1
        }
    }

    impl std::fmt::Display for Parser {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            if f.alternate() { write!(f, "p") } else { write!(f, "parser") }
        }
    }

    pub trait Rule {
        fn holds(&self, a: i32) -> bool {
            a >= 0
        }
    }

    pub fn outer(a: i32) -> bool {
        fn inner(b: i32) -> bool {
            b > 4
        }
        inner(a) || a > 5
    }
}
