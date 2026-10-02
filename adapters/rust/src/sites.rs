//! Where a defect can be cast in one Rust file.
//!
//! The AST says what and where; the source only confirms that the expected
//! token sits at the reported position. A node whose text cannot be pinned
//! down exactly yields no site rather than a guess, and a site whose wekufe
//! no longer parses is dropped before anyone is asked to cast it.
//!
//! What is *not* proposed matters as much as what is. The parser is `syn`,
//! which is the language's own, so this never scans text: a literal inside a
//! string, a comment or a doc is not a node and cannot become a site.
//!
//! Not proposed, on purpose:
//! - anything under `#[cfg(test)]`, `#[test]` or `#[bench]`: mutating the
//!   oracle is not a measurement of the oracle;
//! - the body of an `unsafe fn`, where a wekufe that compiles can be
//!   undefined behaviour rather than a failing test;
//! - patterns, types and attributes, whose literals are structure, not
//!   values — `[u8; 4]` is a type, and `4` is not a number a test can see;
//! - the arguments of a macro, which are tokens `syn` does not parse.

use crate::source::{Position, Source};
use crate::spell::Spell;
use proc_macro2::Span;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use syn::spanned::Spanned;
use syn::visit::{self, Visit};
use syn::{
    BinOp, Expr, ExprBinary, ExprCall, ExprIf, ExprLit, ExprMatch, ExprRepeat, ExprUnary, Lit, UnOp,
};

/// One place a spell applies, as the protocol states it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Site {
    pub site_id: String,
    pub file: String,
    pub enclosing: Option<String>,
    pub ordinal: usize,
    pub start: Position,
    pub end: Position,
    pub spell: Spell,
    pub original: String,
    pub replacement: String,
}

/// What a file yielded, and how many candidates were thrown away because
/// their wekufe would not parse.
#[derive(Debug, PartialEq, Eq)]
pub struct Found {
    pub sites: Vec<Site>,
    pub dropped: usize,
}

/// A file that cannot be searched.
#[derive(Debug)]
pub struct ParseError(pub String);

/// Search one file.
pub fn find(
    file: &str,
    text: &str,
    spells: &[Spell],
    exclude_calls: &[String],
) -> Result<Found, ParseError> {
    let ast = syn::parse_file(text).map_err(|e| {
        let at = e.span().start();
        ParseError(format!("line {}: {}", at.line, e))
    })?;
    let src = Source::new(text);
    let file_hash = hex(&Sha256::digest(text.as_bytes()));

    let mut walker = Walker {
        src: &src,
        spells,
        exclude: exclude_calls,
        scope: Vec::new(),
        found: Vec::new(),
    };
    walker.visit_file(&ast);

    let resolved = in_source_order(walker.found);

    let (kept, dropped): (Vec<_>, Vec<_>) = resolved.into_iter().partition(|c| splices(&src, c));
    Ok(Found {
        sites: number(file, &file_hash, &src, kept),
        dropped: dropped.len(),
    })
}

// Source order, and one candidate where the walk reached the same one twice.
fn in_source_order(mut found: Vec<Candidate>) -> Vec<Candidate> {
    found.sort_by(|a, b| {
        (a.from, a.to, a.spell, &a.replacement).cmp(&(b.from, b.to, b.spell, &b.replacement))
    });
    found.dedup_by(|a, b| {
        a.from == b.from && a.to == b.to && a.spell == b.spell && a.replacement == b.replacement
    });
    found
}

// A candidate before it is a site: spans in bytes, and where it lives.
struct Candidate {
    spell: Spell,
    from: usize,
    to: usize,
    replacement: String,
    enclosing: Option<String>,
}

// The wekufe has to differ from the original and has to still be a Rust
// file. Whether it type-checks is for the compiler to say, and a cast
// that fails there is reported as a compile error, not hidden.
fn splices(src: &Source, c: &Candidate) -> bool {
    match src.slice(c.from, c.to) {
        Some(original) if original != c.replacement => {
            syn::parse_file(&src.splice(c.from, c.to, &c.replacement)).is_ok()
        }
        _ => false,
    }
}

// Ordinal: the 1-based occurrence of `(spell, original)` within the
// enclosing declaration, in source order. It is what lets a declared
// equivalent survive an edit elsewhere in the file, where a line number
// would not.
fn number(file: &str, file_hash: &str, src: &Source, kept: Vec<Candidate>) -> Vec<Site> {
    let mut seen: HashMap<(Option<String>, Spell, String), usize> = HashMap::new();
    let mut sites = Vec::with_capacity(kept.len());

    for c in kept {
        let original = src.slice(c.from, c.to).unwrap_or("").to_string();
        let key = (c.enclosing.clone(), c.spell, original.clone());
        let n = seen.entry(key).or_insert(0);
        *n += 1;
        let ordinal = *n;
        let id = hex(&Sha256::digest(
            format!(
                "{file_hash}|{}|{}|{}|{}",
                c.from, c.to, c.spell, c.replacement
            )
            .as_bytes(),
        ));
        sites.push(Site {
            site_id: id[..12].to_string(),
            file: file.to_string(),
            enclosing: c.enclosing,
            ordinal,
            start: src.position(c.from),
            end: src.position(c.to),
            spell: c.spell,
            original,
            replacement: c.replacement,
        });
    }
    sites
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

// ---- the walk ---------------------------------------------------------------

struct Walker<'a> {
    src: &'a Source<'a>,
    spells: &'a [Spell],
    exclude: &'a [String],
    scope: Vec<String>,
    found: Vec<Candidate>,
}

impl<'a> Walker<'a> {
    fn wants(&self, spell: Spell) -> bool {
        self.spells.contains(&spell)
    }

    fn enclosing(&self) -> Option<String> {
        if self.scope.is_empty() {
            None
        } else {
            Some(self.scope.join("::"))
        }
    }

    // A span of tokens in `syn`'s terms, as byte offsets.
    fn bytes(&self, span: Span) -> Option<(usize, usize)> {
        let (s, e) = (span.start(), span.end());
        Some((
            self.src.byte_at(s.line, s.column)?,
            self.src.byte_at(e.line, e.column)?,
        ))
    }

    fn propose(&mut self, spell: Spell, span: Span, expected: Option<&str>, replacement: String) {
        if !self.wants(spell) {
            return;
        }
        let Some((from, to)) = self.bytes(span) else {
            return;
        };
        if let Some(text) = expected {
            // The token must really be where `syn` says it is.
            if self.src.slice(from, to) != Some(text) {
                return;
            }
        }
        self.found.push(Candidate {
            spell,
            from,
            to,
            replacement,
            enclosing: self.enclosing(),
        });
    }

    fn within<F: FnOnce(&mut Self)>(&mut self, name: Option<String>, body: F) {
        let pushed = name.is_some();
        if let Some(n) = name {
            self.scope.push(n);
        }
        body(self);
        if pushed {
            self.scope.pop();
        }
    }
}

impl<'a, 'ast> Visit<'ast> for Walker<'a> {
    fn visit_item_mod(&mut self, i: &'ast syn::ItemMod) {
        if is_test_only(&i.attrs) {
            return;
        }
        self.within(Some(i.ident.to_string()), |w| visit::visit_item_mod(w, i));
    }

    fn visit_item_fn(&mut self, i: &'ast syn::ItemFn) {
        if is_test_only(&i.attrs) || is_unsafe(&i.sig) {
            return;
        }
        self.within(Some(i.sig.ident.to_string()), |w| {
            visit::visit_item_fn(w, i)
        });
    }

    fn visit_item_impl(&mut self, i: &'ast syn::ItemImpl) {
        if is_test_only(&i.attrs) {
            return;
        }
        let who = type_name(&i.self_ty);
        let name = match &i.trait_ {
            Some((path, _)) => format!("<{who} as {}>", last_segment(path)),
            None => who,
        };
        self.within(Some(name), |w| visit::visit_item_impl(w, i));
    }

    fn visit_item_trait(&mut self, i: &'ast syn::ItemTrait) {
        if is_test_only(&i.attrs) {
            return;
        }
        self.within(Some(i.ident.to_string()), |w| visit::visit_item_trait(w, i));
    }

    fn visit_impl_item_fn(&mut self, i: &'ast syn::ImplItemFn) {
        if is_test_only(&i.attrs) || is_unsafe(&i.sig) {
            return;
        }
        self.within(Some(i.sig.ident.to_string()), |w| {
            visit::visit_impl_item_fn(w, i)
        });
    }

    fn visit_trait_item_fn(&mut self, i: &'ast syn::TraitItemFn) {
        if is_test_only(&i.attrs) || is_unsafe(&i.sig) {
            return;
        }
        self.within(Some(i.sig.ident.to_string()), |w| {
            visit::visit_trait_item_fn(w, i)
        });
    }

    fn visit_item_const(&mut self, i: &'ast syn::ItemConst) {
        if is_test_only(&i.attrs) {
            return;
        }
        self.within(Some(i.ident.to_string()), |w| visit::visit_item_const(w, i));
    }

    fn visit_item_static(&mut self, i: &'ast syn::ItemStatic) {
        if is_test_only(&i.attrs) {
            return;
        }
        self.within(Some(i.ident.to_string()), |w| {
            visit::visit_item_static(w, i)
        });
    }

    // Structure, not values: `[u8; 4]` and `Foo<3>` name a type, and a
    // number inside one is not something a test can observe.
    fn visit_type(&mut self, _: &'ast syn::Type) {}
    fn visit_generic_argument(&mut self, _: &'ast syn::GenericArgument) {}
    fn visit_pat(&mut self, i: &'ast syn::Pat) {
        // The pattern is structure, but a guard is a condition a test can
        // observe, and in `syn` 3 it hangs off the pattern.
        if let syn::Pat::Guard(g) = i {
            self.visit_expr(&g.guard);
        }
    }
    fn visit_attribute(&mut self, _: &'ast syn::Attribute) {}

    fn visit_expr_repeat(&mut self, i: &'ast ExprRepeat) {
        // The element is a value; the length is part of the type.
        self.visit_expr(&i.expr);
    }

    fn visit_expr_call(&mut self, i: &'ast ExprCall) {
        if let Expr::Path(p) = &*i.func {
            let name = p
                .path
                .segments
                .iter()
                .map(|s| s.ident.to_string())
                .collect::<Vec<_>>()
                .join("::");
            if self.exclude.iter().any(|pattern| matches(&name, pattern)) {
                return;
            }
        }
        visit::visit_expr_call(self, i);
    }

    fn visit_expr_binary(&mut self, i: &'ast ExprBinary) {
        let span = i.op.span();
        match i.op {
            BinOp::Ge(_) => self.propose(Spell::Compare, span, Some(">="), ">".into()),
            BinOp::Gt(_) => self.propose(Spell::Compare, span, Some(">"), ">=".into()),
            BinOp::Le(_) => self.propose(Spell::Compare, span, Some("<="), "<".into()),
            BinOp::Lt(_) => self.propose(Spell::Compare, span, Some("<"), "<=".into()),
            BinOp::Eq(_) => self.propose(Spell::Compare, span, Some("=="), "!=".into()),
            BinOp::Ne(_) => self.propose(Spell::Compare, span, Some("!="), "==".into()),
            // `a && let Some(x) = b` binds, and `||` cannot bind: swapping
            // them is a wekufe that cannot compile for a reason that has
            // nothing to do with the tests.
            BinOp::And(_) if !binds(&i.left) && !binds(&i.right) => {
                self.propose(Spell::Connect, span, Some("&&"), "||".into())
            }
            BinOp::Or(_) if !binds(&i.left) && !binds(&i.right) => {
                self.propose(Spell::Connect, span, Some("||"), "&&".into())
            }
            _ => {}
        }
        visit::visit_expr_binary(self, i);
    }

    fn visit_expr_unary(&mut self, i: &'ast ExprUnary) {
        if let UnOp::Not(_) = i.op {
            self.propose(Spell::Negate, i.op.span(), Some("!"), String::new());
        }
        visit::visit_expr_unary(self, i);
    }

    fn visit_expr_if(&mut self, i: &'ast ExprIf) {
        if !binds(&i.cond) {
            if let Some((from, to)) = self.bytes(i.cond.span()) {
                if let Some(text) = self.src.slice(from, to) {
                    let negated = format!("!({text})");
                    self.propose(Spell::Negate, i.cond.span(), None, negated);
                }
            }
        }
        visit::visit_expr_if(self, i);
    }

    fn visit_expr_lit(&mut self, i: &'ast ExprLit) {
        match &i.lit {
            Lit::Bool(b) => {
                let flipped = if b.value { "false" } else { "true" };
                self.propose(Spell::Literal, b.span(), None, flipped.into());
            }
            Lit::Int(n) => {
                if let Some(next) = succeeding(&n.to_string()) {
                    self.propose(Spell::Literal, n.span(), None, next);
                }
            }
            Lit::Str(s) if !s.value().is_empty() => {
                self.propose(Spell::Literal, s.span(), None, "\"\"".into());
            }
            _ => {}
        }
    }

    fn visit_expr_match(&mut self, i: &'ast ExprMatch) {
        // An arm can only go if something still catches what it caught:
        // without a catch-all the wekufe stops being exhaustive, which is a
        // compile error for a reason no test can have an opinion about.
        if let Some(catch_all) = i.arms.iter().position(is_catch_all) {
            for (at, arm) in i.arms.iter().enumerate() {
                if at != catch_all {
                    self.propose(Spell::Arm, arm.span(), None, String::new());
                }
            }
        }
        visit::visit_expr_match(self, i);
    }
}

// ---- small judgements -------------------------------------------------------

// `#[cfg(test)]`, `#[test]`, `#[tokio::test]`, `#[bench]`: code that exists
// to be run by the suite, which is the oracle.
fn is_test_only(attrs: &[syn::Attribute]) -> bool {
    attrs.iter().any(|a| {
        let last = a.path().segments.last().map(|s| s.ident.to_string());
        match last.as_deref() {
            Some("test") | Some("bench") => true,
            // `cfg(test)` and `cfg(all(test, ...))`; `cfg(not(test))` is
            // the code that ships, and that is exactly what is measured.
            Some("cfg") => match a.meta.require_list() {
                Ok(list) => {
                    mentions(list.tokens.clone(), "test") && !mentions(list.tokens.clone(), "not")
                }
                Err(_) => false,
            },
            _ => false,
        }
    })
}

// Whether a token stream names an identifier anywhere, groups included.
fn mentions(tokens: proc_macro2::TokenStream, word: &str) -> bool {
    tokens.into_iter().any(|tree| match tree {
        proc_macro2::TokenTree::Ident(i) => i == word,
        proc_macro2::TokenTree::Group(g) => mentions(g.stream(), word),
        _ => false,
    })
}

fn is_unsafe(sig: &syn::Signature) -> bool {
    matches!(sig.safety, syn::Safety::Unsafe(_))
}

// An expression that introduces a binding (`let` in a condition).
fn binds(e: &Expr) -> bool {
    match e {
        Expr::Let(_) => true,
        Expr::Paren(p) => binds(&p.expr),
        Expr::Binary(b) if matches!(b.op, BinOp::And(_)) => binds(&b.left) || binds(&b.right),
        _ => false,
    }
}

// A pattern that matches everything: `_`, or a lowercase binding. An
// uppercase identifier is a unit variant or a constant, which `syn` cannot
// tell from a binding, so it does not count.
fn is_catch_all(arm: &syn::Arm) -> bool {
    // A guarded arm is `Pat::Guard`, which falls through to `false` below.
    match &arm.pat {
        syn::Pat::Wild(_) => true,
        syn::Pat::Ident(p) => {
            p.subpat.is_none()
                && p.ident
                    .to_string()
                    .chars()
                    .next()
                    .is_some_and(|c| c.is_lowercase() || c == '_')
        }
        _ => false,
    }
}

// The literal one past this one, when that is something exact: plain
// decimal digits and an optional suffix. A hex literal or one with
// underscores would be rewritten into a different notation, which is not
// one defect alone, and a value past its suffix's range is a compile
// error rather than a wekufe.
fn succeeding(token: &str) -> Option<String> {
    let digits: String = token.chars().take_while(|c| c.is_ascii_digit()).collect();
    let suffix = &token[digits.len()..];
    if digits.is_empty() || (digits.len() > 1 && digits.starts_with('0')) {
        return None;
    }
    // Only a real integer suffix: the `x10` of `0x10` and the `b11` of
    // `0b11` are the notation, not a type.
    if !suffix.is_empty() && max_of(suffix).is_none() {
        return None;
    }
    let value: u128 = digits.parse().ok()?;
    let next = value.checked_add(1)?;
    if let Some(max) = max_of(suffix) {
        if next > max {
            return None;
        }
    }
    Some(format!("{next}{suffix}"))
}

fn max_of(suffix: &str) -> Option<u128> {
    Some(match suffix {
        "u8" => u8::MAX as u128,
        "i8" => i8::MAX as u128,
        "u16" => u16::MAX as u128,
        "i16" => i16::MAX as u128,
        "u32" => u32::MAX as u128,
        "i32" => i32::MAX as u128,
        "u64" => u64::MAX as u128,
        "i64" => i64::MAX as u128,
        "u128" => u128::MAX,
        "i128" => i128::MAX as u128,
        "usize" => usize::MAX as u128,
        "isize" => isize::MAX as u128,
        _ => return None,
    })
}

fn type_name(t: &syn::Type) -> String {
    match t {
        syn::Type::Path(p) => p
            .path
            .segments
            .last()
            .map(|s| s.ident.to_string())
            .unwrap_or_else(|| "_".into()),
        syn::Type::Reference(r) => type_name(&r.elem),
        _ => "_".into(),
    }
}

fn last_segment(path: &syn::Path) -> String {
    path.segments
        .last()
        .map(|s| s.ident.to_string())
        .unwrap_or_default()
}

// `exclude_calls` patterns: an exact name, or a prefix ending in `*`.
fn matches(name: &str, pattern: &str) -> bool {
    match pattern.split_once('*') {
        None => name == pattern,
        Some((prefix, "")) => name.starts_with(prefix),
        Some(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::spell::CAST;

    // (spell, original, replacement) of every site, in source order.
    fn found(src: &str) -> Vec<(Spell, String, String)> {
        find("a.rs", src, &CAST, &[])
            .unwrap()
            .sites
            .into_iter()
            .map(|s| (s.spell, s.original, s.replacement))
            .collect()
    }

    fn of(spell: Spell, src: &str) -> Vec<(String, String)> {
        found(src)
            .into_iter()
            .filter(|(s, _, _)| *s == spell)
            .map(|(_, o, r)| (o, r))
            .collect()
    }

    fn pair(o: &str, r: &str) -> (String, String) {
        (o.to_string(), r.to_string())
    }

    // ---- compare ------------------------------------------------------------

    #[test]
    fn a_comparison_is_swapped_for_its_neighbour() {
        let got = of(
            Spell::Compare,
            "fn f(a: i32, b: i32) -> bool { a >= b || a < b || a == b }",
        );

        assert_eq!(got, [pair(">=", ">"), pair("<", "<="), pair("==", "!=")]);
    }

    #[test]
    fn a_comparison_in_a_match_guard_is_a_site() {
        let src = "fn f(n: i32) -> u8 { match n { x if x > 3 => 1, _ => 0 } }";

        assert_eq!(of(Spell::Compare, src), [pair(">", ">=")]);
    }

    // `>>=` and `<<` are not comparisons and share characters with them.
    #[test]
    fn shifts_and_generics_are_not_comparisons() {
        let src = "fn f(mut a: u32) -> Vec<Vec<u8>> { a >>= 1; a <<= 1; Vec::new() }";

        assert!(of(Spell::Compare, src).is_empty());
    }

    // ---- connect ------------------------------------------------------------

    #[test]
    fn and_and_or_are_swapped() {
        let got = of(
            Spell::Connect,
            "fn f(a: bool, b: bool, c: bool) -> bool { a && b || c }",
        );

        // In source order: the `&&` comes before the `||`.
        assert_eq!(got, [pair("&&", "||"), pair("||", "&&")]);
    }

    // `a && let Some(x) = b` binds `x`, and `||` cannot bind: the wekufe would
    // fail to compile for a reason no test has an opinion about.
    #[test]
    fn a_let_chain_is_not_swapped() {
        let src = "fn f(a: bool, b: Option<i32>) -> bool { if a && let Some(x) = b { x > 0 } else { false } }";

        assert!(of(Spell::Connect, src).is_empty());
        // The comparison inside is still a real site.
        assert_eq!(of(Spell::Compare, src), [pair(">", ">=")]);
    }

    // ---- negate -------------------------------------------------------------

    #[test]
    fn an_if_condition_is_negated_whole() {
        let got = of(
            Spell::Negate,
            "fn f(a: i32) -> i32 { if a > 3 && a < 9 { 1 } else { 0 } }",
        );

        assert_eq!(got, [pair("a > 3 && a < 9", "!(a > 3 && a < 9)")]);
    }

    #[test]
    fn a_not_is_removed() {
        let got = of(Spell::Negate, "fn f(a: bool) -> bool { !a }");

        assert_eq!(got, [pair("!", "")]);
    }

    // `if let` binds, and there is no negation of a binding.
    #[test]
    fn an_if_let_is_not_negated() {
        let got = of(
            Spell::Negate,
            "fn f(a: Option<i32>) -> i32 { if let Some(x) = a { x } else { 0 } }",
        );

        assert!(got.is_empty());
    }

    // ---- literal ------------------------------------------------------------

    #[test]
    fn integers_booleans_and_strings_have_a_neighbour() {
        let got = of(
            Spell::Literal,
            r#"fn f() -> (u32, bool, &'static str) { (0, true, "hi") }"#,
        );

        assert_eq!(
            got,
            [
                pair("0", "1"),
                pair("true", "false"),
                pair("\"hi\"", "\"\"")
            ]
        );
    }

    #[test]
    fn an_integer_keeps_its_suffix() {
        let got = of(Spell::Literal, "fn f() -> u8 { 41u8 }");

        assert_eq!(got, [pair("41u8", "42u8")]);
    }

    // One past the largest value is a compile error, not a defect.
    #[test]
    fn an_integer_at_the_top_of_its_type_has_no_neighbour() {
        assert!(of(Spell::Literal, "fn f() -> u8 { 255u8 }").is_empty());
        assert!(of(Spell::Literal, "fn f() -> i8 { 127i8 }").is_empty());
    }

    // Rewriting `0x10` as `17` is a change of notation, and `1_000` as `1001`
    // loses the grouping: neither is one defect alone.
    #[test]
    fn a_literal_in_another_notation_is_left_alone() {
        let src = "fn f() -> u32 { 0x10 + 1_000 + 0b11 + 007 }";

        assert!(of(Spell::Literal, src).is_empty());
    }

    #[test]
    fn an_empty_string_has_no_neighbour() {
        assert!(of(Spell::Literal, r#"fn f() -> &'static str { "" }"#).is_empty());
    }

    // `[u8; 4]` is a type and `4` is not a number a test can see; but the
    // element of `[0u8; 4]` is a value.
    #[test]
    fn the_length_of_an_array_is_structure_and_its_element_is_not() {
        let got = of(Spell::Literal, "fn f() -> [u8; 4] { [0u8; 4] }");

        assert_eq!(got, [pair("0u8", "1u8")]);
    }

    #[test]
    fn a_literal_in_a_pattern_is_structure() {
        let src = "fn f(n: i32) -> bool { match n { 0 => true, 1 | 2 => false, _ => true } }";

        // The `true`/`false` bodies are values; the `0`, `1`, `2` are patterns.
        let got = of(Spell::Literal, src);

        assert!(
            got.iter().all(|(o, _)| o == "true" || o == "false"),
            "{got:?}"
        );
    }

    // Tokens inside a macro are not parsed, so they are not nodes.
    #[test]
    fn the_arguments_of_a_macro_are_opaque() {
        let src = r#"fn f() { println!("hello {}", 1 + 2); }"#;

        assert!(found(src).is_empty());
    }

    // ---- arm ----------------------------------------------------------------

    #[test]
    fn an_arm_goes_only_when_a_catch_all_remains() {
        let src = "fn f(n: i32) -> u8 { match n { 1 => 10, 2 => 20, _ => 0 } }";

        assert_eq!(
            of(Spell::Arm, src),
            [pair("1 => 10,", ""), pair("2 => 20,", "")]
        );
    }

    // Without a catch-all the wekufe stops being exhaustive, which is a
    // compile error for a reason no test can have an opinion about.
    #[test]
    fn a_match_with_no_catch_all_proposes_no_arm() {
        let src = "enum E { A, B } fn f(e: E) -> u8 { match e { E::A => 1, E::B => 2 } }";

        assert!(of(Spell::Arm, src).is_empty());
    }

    #[test]
    fn a_lowercase_binding_catches_everything_and_an_uppercase_one_does_not() {
        let lower = "fn f(n: Option<u8>) -> u8 { match n { Some(1) => 1, other => 0 } }";
        let upper = "fn f(n: Option<u8>) -> u8 { match n { Some(_) => 1, None => 0 } }";

        assert_eq!(of(Spell::Arm, lower), [pair("Some(1) => 1,", "")]);
        assert!(of(Spell::Arm, upper).is_empty());
    }

    #[test]
    fn a_guarded_wildcard_does_not_catch_everything() {
        let src = "fn f(n: i32) -> u8 { match n { 1 => 1, _ if n > 5 => 2, _ => 0 } }";

        // The last arm is the real catch-all; the guarded one is deletable.
        let arms = of(Spell::Arm, src);

        assert!(arms.contains(&pair("1 => 1,", "")));
        assert!(arms.contains(&pair("_ if n > 5 => 2,", "")));
        assert!(!arms.iter().any(|(o, _)| o == "_ => 0"));
    }

    // ---- what is never proposed -----------------------------------------------

    #[test]
    fn test_code_is_never_mutated() {
        let src = r#"
            pub fn real(a: i32) -> bool { a > 1 }

            #[cfg(test)]
            mod tests {
                #[test]
                fn t() { assert!(super::real(2) == true); }
            }

            #[test]
            fn standalone() { assert!(1 < 2); }
        "#;

        assert_eq!(of(Spell::Compare, src), [pair(">", ">=")]);
        assert!(of(Spell::Literal, src).iter().all(|(o, _)| o != "true"));
    }

    // `cfg(not(test))` is the code that ships, and that is what is measured.
    #[test]
    fn code_that_ships_only_outside_tests_is_measured() {
        let src = "#[cfg(not(test))] pub fn f(a: i32) -> bool { a > 1 }";

        assert_eq!(of(Spell::Compare, src), [pair(">", ">=")]);
    }

    // A wekufe that compiles in unsafe code can be undefined behaviour, which
    // is not a failing test.
    #[test]
    fn an_unsafe_function_is_not_mutated() {
        let src =
            "pub unsafe fn f(a: *const i32) -> bool { *a > 1 } pub fn g(a: i32) -> bool { a > 1 }";

        assert_eq!(of(Spell::Compare, src).len(), 1);
    }

    #[test]
    fn a_comment_or_a_doc_is_not_a_node() {
        let src = "/// returns a >= b\n// and a == b\nfn f() {}";

        assert!(found(src).is_empty());
    }

    #[test]
    fn a_call_matching_exclude_calls_gets_no_sites() {
        let src = "fn f(a: i32) { tracing::debug(a > 1); other(a > 2); }";
        let got = find("a.rs", src, &CAST, &["tracing::*".to_string()]).unwrap();

        // Nothing inside `tracing::debug(..)`, which is its `>` and its `1`...
        let inside = src.find("tracing").unwrap()..src.find("; other").unwrap();
        assert!(
            got.sites.iter().all(|s| !inside.contains(&s.start.byte)),
            "{:?}",
            got.sites
        );
        // ...and the call beside it is measured as usual: its `>` and its `2`.
        let originals: Vec<_> = got.sites.iter().map(|s| s.original.as_str()).collect();
        assert_eq!(originals, [">", "2"]);
    }

    // ---- where a site lives -----------------------------------------------------

    #[test]
    fn a_site_knows_its_enclosing_declaration() {
        let src = r#"
            mod parser {
                pub struct P;
                impl P { pub fn next(&self, a: i32) -> bool { a > 1 } }
                impl std::fmt::Display for P {
                    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result { write!(f, "p") }
                }
                pub const LIMIT: bool = 1 > 0;
            }
            pub fn free(a: i32) -> bool { a > 2 }
        "#;
        let mut names: Vec<_> = find("a.rs", src, &CAST, &[])
            .unwrap()
            .sites
            .into_iter()
            .filter(|s| s.spell == Spell::Compare)
            .map(|s| s.enclosing.unwrap())
            .collect();
        names.sort();

        assert_eq!(names, ["free", "parser::LIMIT", "parser::P::next"]);
    }

    #[test]
    fn a_trait_impl_is_named_by_its_trait() {
        let src = "struct S; impl PartialEq for S { fn eq(&self, o: &S) -> bool { 1 > 0 } }";
        let s = &find("a.rs", src, &[Spell::Compare], &[]).unwrap().sites[0];

        assert_eq!(s.enclosing.as_deref(), Some("<S as PartialEq>::eq"));
    }

    // The ordinal is what lets a declared equivalent survive an edit elsewhere
    // in the file, where a line number would not.
    #[test]
    fn the_ordinal_counts_a_repeated_original_within_its_declaration() {
        let src = "fn f(a: i32, b: i32) -> bool { a > 1 && b > 2 } fn g(a: i32) -> bool { a > 3 }";
        let sites = find("a.rs", src, &[Spell::Compare], &[]).unwrap().sites;
        let ord: Vec<_> = sites
            .iter()
            .map(|s| (s.enclosing.clone().unwrap(), s.ordinal))
            .collect();

        assert_eq!(
            ord,
            [
                ("f".to_string(), 1),
                ("f".to_string(), 2),
                ("g".to_string(), 1)
            ]
        );
    }

    // ---- the span ---------------------------------------------------------------

    // Everything the protocol says about a position, on a line that has
    // multibyte text before the token: the character column and the byte
    // offset are different numbers.
    #[test]
    fn a_span_is_exact_in_characters_and_in_bytes() {
        let src = "fn f(a: i32) -> bool { let _é = \"é\"; a >= 1 }";
        let site = &find("a.rs", src, &[Spell::Compare], &[]).unwrap().sites[0];

        assert_eq!(&src[site.start.byte..site.end.byte], ">=");
        assert_eq!(site.original, ">=");
        assert_eq!(site.start.col, src[..site.start.byte].chars().count() + 1);
        assert_ne!(site.start.col, site.start.byte + 1);
    }

    #[test]
    fn a_site_id_is_stable_for_the_same_file_and_changes_when_it_changes() {
        let a = find("a.rs", "fn f(a: i32) -> bool { a > 1 }", &CAST, &[])
            .unwrap()
            .sites;
        let b = find("a.rs", "fn f(a: i32) -> bool { a > 1 }", &CAST, &[])
            .unwrap()
            .sites;
        let c = find("a.rs", "fn f(a: i32) -> bool { a > 2 }", &CAST, &[])
            .unwrap()
            .sites;

        assert_eq!(a[0].site_id, b[0].site_id);
        assert_ne!(a[0].site_id, c[0].site_id);
        assert_eq!(a[0].site_id.len(), 12);
    }

    // ---- files that cannot be read -------------------------------------------------

    #[test]
    fn a_file_that_does_not_parse_is_an_error_naming_the_line() {
        let err = find("a.rs", "fn f( {\n", &CAST, &[]).unwrap_err();

        assert!(err.0.starts_with("line 1"), "{}", err.0);
    }

    // Every site's wekufe still parses: a candidate that would not is dropped
    // before anyone is asked to cast it.
    #[test]
    fn every_site_is_a_wekufe_that_still_parses() {
        let src = r#"
            pub fn f(n: i32, o: Option<i32>) -> i32 {
                if n > 0 && !(n == 3) { match n { 1 => 2, 2 => 3, _ => 4 } } else { 0 }
            }
        "#;
        let sites = find("a.rs", src, &CAST, &[]).unwrap().sites;

        assert!(!sites.is_empty());
        for s in sites {
            let wekufe = format!(
                "{}{}{}",
                &src[..s.start.byte],
                s.replacement,
                &src[s.end.byte..]
            );
            assert!(syn::parse_file(&wekufe).is_ok(), "{s:?}");
        }
    }

    fn attrs_of(src: &str) -> Vec<syn::Attribute> {
        syn::parse_str::<syn::ItemFn>(src).unwrap().attrs
    }

    #[test]
    fn what_exists_to_be_run_by_the_suite_is_test_only() {
        let test_only = [
            "#[test] fn f() {}",
            "#[bench] fn f() {}",
            "#[tokio::test] fn f() {}",
            "#[cfg(test)] fn f() {}",
            "#[cfg(all(test, unix))] fn f() {}",
            "#[inline] #[test] fn f() {}",
        ];
        for src in test_only {
            assert!(is_test_only(&attrs_of(src)), "{src}");
        }
        let shipped = [
            "fn f() {}",
            "#[inline] fn f() {}",
            "#[cfg(not(test))] fn f() {}",
            "#[cfg(unix)] fn f() {}",
            "#[cfg(feature = \"test\")] fn f() {}",
            "#[cfg] fn f() {}",
            "#[test_case] fn f() {}",
            "#[doc = \"a test\"] fn f() {}",
        ];
        for src in shipped {
            assert!(!is_test_only(&attrs_of(src)), "{src}");
        }
    }

    #[test]
    fn a_word_is_found_in_a_token_stream_at_any_depth_and_only_as_an_identifier() {
        let tokens = |s: &str| s.parse::<proc_macro2::TokenStream>().unwrap();

        assert!(mentions(tokens("test"), "test"));
        assert!(mentions(tokens("all(unix, test)"), "test"));
        assert!(mentions(tokens("all(any(test))"), "test"));
        assert!(!mentions(tokens("feature = \"test\""), "test"));
        assert!(!mentions(tokens("1 + 2, 'x'"), "test"));
        assert!(!mentions(tokens(""), "test"));
    }

    #[test]
    fn an_expression_binds_when_a_let_is_in_it() {
        let expr = |s: &str| syn::parse_str::<Expr>(s).unwrap();

        assert!(binds(&expr("let Some(x) = o")));
        assert!(binds(&expr("(let Some(x) = o)")));
        assert!(binds(&expr("a && let Some(x) = o")));
        assert!(binds(&expr("let Some(x) = o && b")));
        assert!(!binds(&expr("a && b")));
        assert!(!binds(&expr("a || b")));
        assert!(!binds(&expr("(a)")));
        assert!(!binds(&expr("a")));
    }

    #[test]
    fn a_let_chain_gets_no_connect_wekufe_from_either_side() {
        let chain = |cond: &str| {
            of(
                Spell::Connect,
                &format!("pub fn f(o: Option<i32>, b: bool) {{ if {cond} {{}} }}"),
            )
        };

        assert!(chain("let Some(x) = o && b").is_empty());
        assert!(chain("b && let Some(x) = o").is_empty());
        assert!(chain("b || b && let Some(x) = o").is_empty());
        assert_eq!(chain("b && b"), [("&&".to_string(), "||".to_string())]);
        assert_eq!(chain("b || b"), [("||".to_string(), "&&".to_string())]);
    }

    #[test]
    fn a_trait_method_gets_sites_unless_it_is_test_only_or_unsafe() {
        let sites = |method: &str| {
            of(
                Spell::Compare,
                &format!("pub trait T {{ {method} fn f(&self, a: i32) -> bool {{ a >= 1 }} }}"),
            )
        };

        assert_eq!(sites(""), [(">=".to_string(), ">".to_string())]);
        assert!(sites("#[cfg(test)]").is_empty());
        assert!(sites("unsafe").is_empty());
    }

    #[test]
    fn a_literal_is_succeeded_within_the_range_of_its_type() {
        let ranges: [(&str, u128); 12] = [
            ("u8", u8::MAX as u128),
            ("i8", i8::MAX as u128),
            ("u16", u16::MAX as u128),
            ("i16", i16::MAX as u128),
            ("u32", u32::MAX as u128),
            ("i32", i32::MAX as u128),
            ("u64", u64::MAX as u128),
            ("i64", i64::MAX as u128),
            ("u128", u128::MAX),
            ("i128", i128::MAX as u128),
            ("usize", usize::MAX as u128),
            ("isize", isize::MAX as u128),
        ];
        for (suffix, max) in ranges {
            assert_eq!(max_of(suffix), Some(max), "{suffix}");
            // The last value that has a successor, and the one that does not.
            assert_eq!(
                succeeding(&format!("{}{suffix}", max - 1)),
                Some(format!("{max}{suffix}")),
                "{suffix}"
            );
            assert_eq!(succeeding(&format!("{max}{suffix}")), None, "{suffix}");
        }
        assert_eq!(max_of("f64"), None);
        assert_eq!(succeeding("7"), Some("8".to_string()));
        assert_eq!(succeeding("0"), Some("1".to_string()));
        assert_eq!(succeeding("007"), None);
        assert_eq!(succeeding("0x10"), None);
        assert_eq!(succeeding("1_000"), None);
    }

    fn candidate(spell: Spell, from: usize, to: usize, replacement: &str) -> Candidate {
        Candidate {
            spell,
            from,
            to,
            replacement: replacement.into(),
            enclosing: None,
        }
    }

    #[test]
    fn candidates_come_in_source_order_and_a_repeated_one_is_kept_once() {
        let found = vec![
            candidate(Spell::Compare, 8, 10, ">"),
            candidate(Spell::Compare, 2, 4, ">"),
            candidate(Spell::Compare, 2, 4, ">"),
            // The same place with another replacement, another spell, or
            // another end is another wekufe.
            candidate(Spell::Compare, 2, 4, "<"),
            candidate(Spell::Negate, 2, 4, ">"),
            candidate(Spell::Compare, 2, 5, ">"),
        ];

        let ordered: Vec<_> = in_source_order(found)
            .into_iter()
            .map(|c| (c.from, c.to, c.spell, c.replacement))
            .collect();

        assert_eq!(
            ordered,
            [
                (2, 4, Spell::Compare, "<".to_string()),
                (2, 4, Spell::Compare, ">".to_string()),
                (2, 4, Spell::Negate, ">".to_string()),
                (2, 5, Spell::Compare, ">".to_string()),
                (8, 10, Spell::Compare, ">".to_string()),
            ]
        );
    }

    #[test]
    fn a_wekufe_is_kept_only_when_it_differs_and_still_parses() {
        let text = "fn f(a: i32) -> bool { a >= 1 }";
        let src = Source::new(text);
        let at = text.find(">=").unwrap();

        assert!(splices(&src, &candidate(Spell::Compare, at, at + 2, ">")));
        // Nothing changes, so nothing is measured.
        assert!(!splices(&src, &candidate(Spell::Compare, at, at + 2, ">=")));
        // A replacement that is not Rust is dropped before anyone casts it.
        assert!(!splices(
            &src,
            &candidate(Spell::Compare, at, at + 2, "=>=")
        ));
        // A span outside the text is not a site at all.
        assert!(!splices(&src, &candidate(Spell::Compare, 900, 902, ">")));
    }

    #[test]
    fn an_impl_method_gets_sites_unless_it_is_test_only_or_unsafe() {
        let sites = |method: &str| {
            of(
                Spell::Compare,
                &format!(
                    "pub struct S; impl S {{ {method} fn f(&self, a: i32) -> bool {{ a >= 1 }} }}"
                ),
            )
        };

        assert_eq!(sites(""), [(">=".to_string(), ">".to_string())]);
        assert!(sites("#[cfg(test)]").is_empty());
        assert!(sites("unsafe").is_empty());
    }

    #[test]
    fn a_literal_with_a_leading_zero_is_not_succeeded() {
        assert_eq!(succeeding("01"), None);
        assert_eq!(succeeding("00"), None);
        assert_eq!(succeeding("10"), Some("11".to_string()));
    }

    #[test]
    fn a_type_is_named_by_its_last_segment_through_references() {
        let name = |t: &str| type_name(&syn::parse_str::<syn::Type>(t).unwrap());

        assert_eq!(name("Foo"), "Foo");
        assert_eq!(name("a::b::Foo<T>"), "Foo");
        assert_eq!(name("&Foo"), "Foo");
        assert_eq!(name("&'a mut Foo"), "Foo");
        assert_eq!(name("&&Foo"), "Foo");
        assert_eq!(name("[u8]"), "_");
        assert_eq!(name("(A, B)"), "_");
        let empty = syn::Type::Path(syn::TypePath {
            attrs: vec![],
            qself: None,
            path: syn::Path {
                leading_colon: None,
                segments: syn::punctuated::Punctuated::new(),
            },
        });
        assert_eq!(type_name(&empty), "_");
    }

    #[test]
    fn a_call_pattern_is_an_exact_name_or_a_prefix_ending_in_a_star() {
        assert!(matches("Logger.info", "Logger.info"));
        assert!(!matches("Logger.info", "Logger.warn"));
        assert!(matches("Logger.info", "Logger.*"));
        assert!(matches("Logger.", "Logger.*"));
        assert!(!matches("Other.info", "Logger.*"));
        // A star anywhere but the end is not a pattern this reads.
        assert!(!matches("Logger.info", "Log*r.info"));
        assert!(!matches("Logger.info", "*Logger.info"));
    }
}
