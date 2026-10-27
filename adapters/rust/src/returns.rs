//! The neutral value a function could return instead of what it computed.
//!
//! The value is read from the signature as written, never inferred: a
//! wekufe that does not type-check costs a build and measures nothing, so
//! a type this cannot name a value for gets no site. That leaves out `()`,
//! generics, `Self`, `impl Trait`, and every type of the project's own.

use syn::{Expr, GenericArgument, Lit, PathArguments, Type};

/// What to return instead of `value` from a function declared to return
/// `ty`, or nothing when there is no such value or `value` already is it.
pub fn neutral(ty: &Type, value: &Expr) -> Option<String> {
    match ty {
        Type::Paren(p) => neutral(&p.elem, value),
        Type::Group(g) => neutral(&g.elem, value),
        Type::Reference(r) if r.mutability.is_none() && is_named(&r.elem, "str") => {
            (!is_empty_str(value)).then(|| "\"\"".to_string())
        }
        Type::Path(p) if p.qself.is_none() => {
            let last = p.path.segments.last()?;
            let name = last.ident.to_string();
            match name.as_str() {
                "i8" | "i16" | "i32" | "i64" | "i128" | "isize" | "u8" | "u16" | "u32" | "u64"
                | "u128" | "usize"
                    if bare(&last.arguments) =>
                {
                    Some(if is_int(value, 0) { "1" } else { "0" }.to_string())
                }
                "f32" | "f64" if bare(&last.arguments) => {
                    Some(if is_zero_float(value) { "1.0" } else { "0.0" }.to_string())
                }
                "bool" if bare(&last.arguments) => Some(
                    if is_bool(value, false) {
                        "true"
                    } else {
                        "false"
                    }
                    .to_string(),
                ),
                "String" if bare(&last.arguments) => {
                    (!is_new(value)).then(|| format!("{}::new()", written(&p.path)))
                }
                "Vec" => (!is_new(value)).then(|| format!("{}::new()", written(&p.path))),
                "Option" => (!is_path(value, "None")).then(|| "None".to_string()),
                "Result" => {
                    let ok = first_type(&last.arguments)?;
                    let inner = match value {
                        Expr::Call(c) if is_path(&c.func, "Ok") && c.args.len() == 1 => &c.args[0],
                        _ => value,
                    };
                    let inner_value = neutral(ok, inner);
                    match (ok, inner_value) {
                        (Type::Tuple(_), _) => None,
                        (_, Some(v)) => Some(format!("Ok({v})")),
                        (_, None) => None,
                    }
                }
                _ => None,
            }
        }
        _ => None,
    }
}

fn bare(args: &PathArguments) -> bool {
    matches!(args, PathArguments::None)
}

fn is_named(ty: &Type, name: &str) -> bool {
    match ty {
        Type::Path(p) => p.qself.is_none() && p.path.is_ident(name),
        _ => false,
    }
}

fn first_type(args: &PathArguments) -> Option<&Type> {
    match args {
        PathArguments::AngleBracketed(a) => a.args.iter().find_map(|g| match g {
            GenericArgument::Type(t) => Some(t),
            _ => None,
        }),
        _ => None,
    }
}

// The path as the signature spells it, without its generic arguments, so
// `alloc::vec::Vec<u8>` becomes `alloc::vec::Vec`: whatever names the type
// there names it in the body too.
fn written(path: &syn::Path) -> String {
    let segments: Vec<String> = path.segments.iter().map(|s| s.ident.to_string()).collect();
    let lead = if path.leading_colon.is_some() {
        "::"
    } else {
        ""
    };
    format!("{lead}{}", segments.join("::"))
}

fn is_path(e: &Expr, name: &str) -> bool {
    match e {
        Expr::Path(p) => p.qself.is_none() && p.path.is_ident(name),
        Expr::Paren(p) => is_path(&p.expr, name),
        _ => false,
    }
}

fn is_int(e: &Expr, n: u128) -> bool {
    match e {
        Expr::Lit(l) => matches!(&l.lit, Lit::Int(i) if i.base10_parse::<u128>().ok() == Some(n)),
        Expr::Paren(p) => is_int(&p.expr, n),
        _ => false,
    }
}

fn is_zero_float(e: &Expr) -> bool {
    match e {
        Expr::Lit(l) => match &l.lit {
            Lit::Float(f) => f.base10_parse::<f64>().ok() == Some(0.0),
            Lit::Int(i) => i.base10_parse::<u128>().ok() == Some(0),
            _ => false,
        },
        Expr::Paren(p) => is_zero_float(&p.expr),
        _ => false,
    }
}

fn is_bool(e: &Expr, b: bool) -> bool {
    match e {
        Expr::Lit(l) => matches!(&l.lit, Lit::Bool(v) if v.value == b),
        Expr::Paren(p) => is_bool(&p.expr, b),
        _ => false,
    }
}

fn is_empty_str(e: &Expr) -> bool {
    match e {
        Expr::Lit(l) => matches!(&l.lit, Lit::Str(s) if s.value().is_empty()),
        Expr::Paren(p) => is_empty_str(&p.expr),
        _ => false,
    }
}

// `X::new()`, `X::default()`, `vec![]`, `String::from("")`: already empty.
fn is_new(e: &Expr) -> bool {
    match e {
        Expr::Call(c) => match &*c.func {
            Expr::Path(p) => {
                let last = p.path.segments.last().map(|s| s.ident.to_string());
                match last.as_deref() {
                    Some("new") | Some("default") => c.args.is_empty(),
                    Some("from") => c.args.len() == 1 && is_empty_str(&c.args[0]),
                    _ => false,
                }
            }
            _ => false,
        },
        Expr::Macro(m) => m.mac.path.is_ident("vec") && m.mac.tokens.is_empty(),
        Expr::Paren(p) => is_new(&p.expr),
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn of(ty: &str, value: &str) -> Option<String> {
        neutral(
            &syn::parse_str::<Type>(ty).unwrap(),
            &syn::parse_str::<Expr>(value).unwrap(),
        )
    }

    #[test]
    fn a_number_becomes_zero_and_zero_becomes_one() {
        assert_eq!(of("usize", "self.len"), Some("0".into()));
        assert_eq!(of("i64", "0"), Some("1".into()));
        assert_eq!(of("f64", "x * 2.0"), Some("0.0".into()));
        assert_eq!(of("f32", "0.0"), Some("1.0".into()));
    }

    #[test]
    fn a_bool_becomes_false_and_false_becomes_true() {
        assert_eq!(of("bool", "a > b"), Some("false".into()));
        assert_eq!(of("bool", "false"), Some("true".into()));
    }

    #[test]
    fn a_collection_becomes_empty_as_its_signature_names_it() {
        assert_eq!(
            of("String", "s.trim().to_string()"),
            Some("String::new()".into())
        );
        assert_eq!(
            of("alloc::vec::Vec<u8>", "out"),
            Some("alloc::vec::Vec::new()".into())
        );
        assert_eq!(of("&'static str", "\"name\""), Some("\"\"".into()));
        assert_eq!(of("Option<u8>", "Some(1)"), Some("None".into()));
    }

    #[test]
    fn a_result_keeps_ok_and_empties_what_it_holds() {
        assert_eq!(of("Result<u32, Error>", "Ok(n + 1)"), Some("Ok(0)".into()));
        assert_eq!(of("io::Result<u32>", "Ok(0)"), Some("Ok(1)".into()));
        assert_eq!(
            of("Result<String, E>", "parse(s)"),
            Some("Ok(String::new())".into())
        );
        assert_eq!(of("Result<(), E>", "write(x)"), None);
        assert_eq!(of("Result<Config, E>", "load()"), None);
    }

    #[test]
    fn a_value_that_already_is_the_neutral_one_has_none() {
        assert_eq!(of("Option<u8>", "None"), None);
        assert_eq!(of("String", "String::new()"), None);
        assert_eq!(of("Vec<u8>", "vec![]"), None);
        assert_eq!(of("Vec<u8>", "Vec::default()"), None);
        assert_eq!(of("&str", "\"\""), None);
    }

    #[test]
    fn a_type_this_cannot_name_a_value_for_has_none() {
        for ty in [
            "()",
            "T",
            "Self",
            "impl Iterator<Item = u8>",
            "Config",
            "&[u8]",
            "Box<u8>",
        ] {
            assert_eq!(of(ty, "x"), None, "{ty}");
        }
    }
}
