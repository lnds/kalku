//! Answering requests.
//!
//! One request per line in, one reply per line out, and nothing else may
//! reach stdout: anything a toolchain prints goes to stderr, because the
//! kaikai side banishes a worker for a line it cannot read.

use crate::editions::{self, Cargo, Editions};
use crate::protocol::{self, DecodeError, Hello, Request, SitesRequest, Skipped};
use crate::sites;
use crate::toolchain::{self, Toolchain};
use std::path::{Path, PathBuf};

/// What to do with a reply.
#[derive(Debug, PartialEq, Eq)]
pub enum Step {
    /// Say this and carry on.
    Reply(String),
    /// Say this and leave.
    Stop(String),
}

/// Asks the compiler which toolchain this is.
type Probe = Box<dyn Fn() -> Result<Toolchain, String>>;

/// Builds the answer to "which edition is this file?" for a project root.
type ChooseEditions = Box<dyn FnMut(&Path) -> Box<dyn Editions>>;

pub struct Service {
    adapter: String,
    probe: Probe,
    editions: ChooseEditions,
    hello: Option<Hello>,
    editions_of: Option<Box<dyn Editions>>,
}

impl Service {
    /// The service as it runs: the real compiler, and cargo for editions.
    pub fn new() -> Self {
        Service::with(
            env!("CARGO_PKG_VERSION"),
            Box::new(toolchain::probe),
            Box::new(|root| Box::new(Cargo::new(PathBuf::from(root)))),
        )
    }

    /// A service with its dependencies chosen, which is how it is tested
    /// without a compiler or a Cargo project at hand.
    pub fn with(adapter: &str, probe: Probe, editions: ChooseEditions) -> Self {
        Service {
            adapter: adapter.to_string(),
            probe,
            editions,
            hello: None,
            editions_of: None,
        }
    }

    /// One line in, one step out.
    pub fn handle(&mut self, line: &str) -> Step {
        match protocol::decode(line) {
            Err(e) => Step::Reply(self.refuse(&e)),
            Ok((id, request)) => self.dispatch(id, request),
        }
    }

    /// A request that could not even be read.
    pub fn refuse(&self, e: &DecodeError) -> String {
        protocol::error(
            e.id.unwrap_or(0),
            "bad_request",
            &format!("{}: {}", e.kind.name(), e.detail),
            false,
        )
    }

    fn dispatch(&mut self, id: i64, request: Request) -> Step {
        match request {
            Request::Hello(h) => self.greet(id, h),
            Request::Sites(r) => self.sites(id, r),
            Request::Shutdown => Step::Stop(protocol::bye(id)),
            other => Step::Reply(protocol::error(
                id,
                "not_implemented",
                &format!("this kalku does not answer `{}` yet", name(&other)),
                true,
            )),
        }
    }

    fn greet(&mut self, id: i64, hello: Hello) -> Step {
        if hello.protocol != protocol::PROTOCOL {
            return Step::Reply(protocol::error(
                id,
                "protocol_mismatch",
                &format!(
                    "kalku speaks protocol {}, kaikai side speaks {}",
                    protocol::PROTOCOL,
                    hello.protocol
                ),
                true,
            ));
        }
        let toolchain = match (self.probe)() {
            Ok(t) => t,
            Err(why) => return Step::Reply(protocol::error(id, "toolchain_missing", &why, true)),
        };
        if !toolchain.is_recent_enough() {
            return Step::Reply(protocol::error(
                id,
                "unsupported_toolchain",
                &format!(
                    "{} is older than 1.85, the first release with the 2024 edition kalku measures",
                    toolchain.version_line
                ),
                true,
            ));
        }
        self.editions_of = Some((self.editions)(Path::new(&hello.root)));
        let runtime = format!(
            "{}, edition {}",
            toolchain.version_line,
            editions::SUPPORTED
        );
        self.hello = Some(hello);
        Step::Reply(protocol::ready(id, &self.adapter, &runtime))
    }

    fn sites(&mut self, id: i64, request: SitesRequest) -> Step {
        let (Some(hello), Some(editions)) = (&self.hello, &mut self.editions_of) else {
            return Step::Reply(protocol::error(
                id,
                "not_ready",
                "`hello` has to come first",
                false,
            ));
        };
        let root = PathBuf::from(&hello.root);

        let mut found = Vec::new();
        let mut skipped = Vec::new();
        for file in &request.files {
            match search(&root, file, &request, editions.as_mut()) {
                Ok(mut sites) => found.append(&mut sites),
                Err(Refusal::Fatal(why)) => {
                    return Step::Reply(protocol::error(id, "not_a_cargo_project", &why, true));
                }
                Err(Refusal::Skip(reason, message)) => skipped.push(Skipped {
                    file: file.clone(),
                    reason,
                    message,
                }),
            }
        }
        Step::Reply(protocol::sites_found(id, &found, &skipped))
    }
}

impl Default for Service {
    fn default() -> Self {
        Service::new()
    }
}

// Why one file did not yield sites. A file that cannot be searched is
// reported and the rest go on; a project that cannot be read at all is not
// a thing to skip past.
enum Refusal {
    Skip(String, String),
    Fatal(String),
}

fn search(
    root: &Path,
    file: &str,
    request: &SitesRequest,
    editions: &mut dyn Editions,
) -> Result<Vec<sites::Site>, Refusal> {
    let skip = |reason: &str, message: String| Refusal::Skip(reason.to_string(), message);

    if Path::new(file).extension().and_then(|e| e.to_str()) != Some("rs") {
        return Err(skip(
            "not_rust",
            format!("`{file}` is not a Rust source file"),
        ));
    }
    match editions.of(Path::new(file)).map_err(Refusal::Fatal)? {
        Some(edition) if edition == editions::SUPPORTED => {}
        Some(edition) => return Err(skip("unsupported_edition", editions::unsupported(&edition))),
        None => {
            return Err(skip(
                "not_in_a_package",
                format!("`{file}` belongs to no package of this project"),
            ));
        }
    }
    let text = std::fs::read_to_string(root.join(file))
        .map_err(|e| skip("unreadable", format!("cannot read `{file}`: {e}")))?;
    sites::find(file, &text, &request.spells, &request.exclude_calls)
        .map(|f| f.sites)
        .map_err(|e| skip("parse_error", e.0))
}

fn name(r: &Request) -> &'static str {
    match r {
        Request::Hello(_) => "hello",
        Request::Prepare => "prepare",
        Request::Baseline => "baseline",
        Request::Sites(_) => "sites",
        Request::Cast { .. } => "cast",
        Request::Abort { .. } => "abort",
        Request::Reset => "reset",
        Request::Reload { .. } => "reload",
        Request::Delegate => "delegate",
        Request::Shutdown => "shutdown",
    }
}
