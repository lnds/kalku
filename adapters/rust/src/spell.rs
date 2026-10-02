//! The spells of the shared catalogue, by their wire names.
//!
//! A language may add spells, but a new one gets a shared name through the
//! protocol and not an ad-hoc string, so this is the closed set the protocol
//! states and nothing of Rust's own.

use std::fmt;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Spell {
    Arm,
    Compare,
    Connect,
    Negate,
    Literal,
    Call,
    Await,
    Supervise,
    Foreign,
}

impl Spell {
    pub fn name(self) -> &'static str {
        match self {
            Spell::Arm => "arm",
            Spell::Compare => "compare",
            Spell::Connect => "connect",
            Spell::Negate => "negate",
            Spell::Literal => "literal",
            Spell::Call => "call",
            Spell::Await => "await",
            Spell::Supervise => "supervise",
            Spell::Foreign => "foreign",
        }
    }

    pub fn parse(name: &str) -> Option<Spell> {
        [
            Spell::Arm,
            Spell::Compare,
            Spell::Connect,
            Spell::Negate,
            Spell::Literal,
            Spell::Call,
            Spell::Await,
            Spell::Supervise,
            Spell::Foreign,
        ]
        .into_iter()
        .find(|s| s.name() == name)
    }
}

impl fmt::Display for Spell {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.name())
    }
}

/// What this kalku can cast.
///
/// `call` is not here: dropping a call in Rust almost never keeps the
/// types, so it would propose compile errors and little else, and each of
/// those costs a build. `await` and `supervise` are concurrency spells for
/// the BEAM. A spell is announced when it is true.
pub const CAST: [Spell; 5] = [
    Spell::Arm,
    Spell::Compare,
    Spell::Connect,
    Spell::Negate,
    Spell::Literal,
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_spell_round_trips_through_its_name() {
        for spell in CAST {
            assert_eq!(Spell::parse(spell.name()), Some(spell));
        }
    }

    #[test]
    fn a_name_the_protocol_does_not_have_is_not_a_spell() {
        assert_eq!(Spell::parse("mutate"), None);
        assert_eq!(Spell::parse(""), None);
    }
}
