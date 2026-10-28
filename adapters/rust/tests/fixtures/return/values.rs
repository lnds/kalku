// What a function gives back becomes the neutral value of its declared
// type; a type the signature does not name a value for gets no site.

pub fn count(items: &[u8]) -> usize {
    items.iter().filter(|b| **b > 0).count()
}

pub fn early(n: i32) -> i32 {
    if n < 0 {
        return -n;
    }
    n * 2
}

pub fn ratio(a: f64, b: f64) -> f64 {
    a / b
}

pub fn ready(flag: bool) -> bool {
    flag
}

pub fn greeting(name: &str) -> String {
    format!("hello {name}")
}

pub fn bytes(n: u8) -> std::vec::Vec<u8> {
    vec![n; 3]
}

pub fn first(items: &[u8]) -> Option<u8> {
    items.first().copied()
}

pub fn parse(s: &str) -> Result<u32, std::num::ParseIntError> {
    s.trim().parse::<u32>()
}

pub fn parsed(s: &str) -> Result<u32, std::num::ParseIntError> {
    Ok(s.len() as u32)
}

// Already neutral: nothing to propose.
pub fn nothing() -> Option<u8> {
    None
}

pub fn empty() -> String {
    String::new()
}

// A literal is `literal`'s to change.
pub fn never() -> bool {
    false
}

pub fn label() -> &'static str {
    "label"
}

pub fn zero() -> u32 {
    0
}

// No value the signature names.
pub fn unit(v: &mut Vec<u8>) {
    v.push(1);
}

pub fn saved(v: &mut Vec<u8>) -> Result<(), String> {
    v.push(1);
    Ok(())
}

pub fn own() -> Config {
    Config { size: 1 }
}

pub fn generic<T: Default>() -> T {
    T::default()
}

// A closure's or an async block's `return` is its own.
pub fn closure(items: &[u8]) -> usize {
    let f = |x: u8| -> u8 { return x + 1 };
    items.iter().map(|x| f(*x) as usize).sum()
}

// Computed where no test sees it.
pub const fn constant() -> u32 {
    7
}

// Diverging tails return nothing.
pub fn diverges(n: u32) -> u32 {
    if n > 9 {
        return n;
    }
    unreachable!()
}

pub fn looping(mut n: u32) -> u32 {
    loop {
        if n > 9 {
            return n;
        }
        n += 1;
    }
}

pub trait Sized2 {
    fn size(&self) -> usize {
        4
    }
    fn none(&self) -> usize;
}

pub struct Config {
    size: usize,
}

impl Config {
    pub fn size(&self) -> usize {
        self.size
    }
}

#[cfg(test)]
mod tests {
    fn helper() -> usize {
        3
    }
}
