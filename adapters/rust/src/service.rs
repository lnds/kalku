//! Answering requests.
//!
//! One request per line in, one reply per line out, and nothing else may
//! reach stdout: anything a toolchain prints goes to stderr, because the
//! kaikai side banishes a worker for a line it cannot read.

use crate::editions::{self, Cargo, Editions};
use crate::project::{Build, Cargo as CargoProject, Project, Target};
use crate::protocol::{
    self, DecodeError, Failure, Hello, Outcome, Request, SitesRequest, Skipped, Timed,
};
use crate::sites;
use crate::toolchain::{self, Toolchain};
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::Instant;

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

/// Builds the project a `hello` names, to be built and run in its reni.
type ChooseProject = Box<dyn FnMut(&Hello) -> Box<dyn Project>>;

/// What `prepare` and `baseline` learned, which `cast` runs against.
#[derive(Default)]
struct Session {
    targets: Vec<Target>,
    // A test's id to the target that holds it and its own name.
    tests: HashMap<String, (usize, String)>,
}

pub struct Service {
    adapter: String,
    probe: Probe,
    editions: ChooseEditions,
    choose_project: ChooseProject,
    hello: Option<Hello>,
    editions_of: Option<Box<dyn Editions>>,
    project: Option<Box<dyn Project>>,
    session: Session,
}

impl Service {
    /// The service as it runs: the real compiler, and cargo for editions
    /// and for the project.
    pub fn new() -> Self {
        Service::with(
            env!("CARGO_PKG_VERSION"),
            Box::new(toolchain::probe),
            Box::new(|root| Box::new(Cargo::new(PathBuf::from(root)))),
            Box::new(|hello| {
                Box::new(CargoProject::new(
                    PathBuf::from(&hello.root),
                    PathBuf::from(&hello.reni),
                    hello.env.clone(),
                ))
            }),
        )
    }

    /// A service with its dependencies chosen, which is how it is tested
    /// without a compiler or a Cargo project at hand.
    pub fn with(
        adapter: &str,
        probe: Probe,
        editions: ChooseEditions,
        choose_project: ChooseProject,
    ) -> Self {
        Service {
            adapter: adapter.to_string(),
            probe,
            editions,
            choose_project,
            hello: None,
            editions_of: None,
            project: None,
            session: Session::default(),
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
            Request::Prepare => self.prepare(id),
            Request::Baseline => self.baseline(id),
            Request::Cast {
                wekufe,
                site,
                tests,
            } => self.cast(id, &wekufe, &site, &tests),
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
        self.project = Some((self.choose_project)(&hello));
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

    fn not_ready(id: i64, what: &str) -> Step {
        Step::Reply(protocol::error(id, "not_ready", what, false))
    }

    fn failed(id: i64, code: &str, e: &dyn std::fmt::Display) -> Step {
        Step::Reply(protocol::error(id, code, &e.to_string(), true))
    }

    fn prepare(&mut self, id: i64) -> Step {
        let Some(project) = self.project.as_mut() else {
            return Self::not_ready(id, "`hello` has to come first");
        };
        let started = Instant::now();
        if let Err(e) = project.copy() {
            return Self::failed(
                id,
                "prepare_failed",
                &format!("cannot copy the project: {e}"),
            );
        }
        match project.build() {
            Err(e) => Self::failed(id, "prepare_failed", &format!("cannot run cargo: {e}")),
            Ok(Build::Failed(message)) => Self::failed(id, "prepare_failed", &message),
            Ok(Build::Built(targets)) => {
                let modules = targets.len();
                self.session = Session {
                    targets,
                    tests: HashMap::new(),
                };
                Step::Reply(protocol::prepared(id, millis(started), modules))
            }
        }
    }

    // Every test is run alone once, which is how it gets a duration of its
    // own: stable libtest does not time them.
    fn baseline(&mut self, id: i64) -> Step {
        let Some(project) = self.project.as_mut() else {
            return Self::not_ready(id, "`hello` has to come first");
        };
        if self.session.targets.is_empty() {
            return Self::not_ready(id, "`prepare` has to come first");
        }
        let started = Instant::now();
        let (mut tests, mut failures) = (Vec::new(), Vec::new());
        let mut known = HashMap::new();
        for (index, target) in self.session.targets.iter().enumerate() {
            let names = match project.list(target) {
                Ok(names) => names,
                Err(e) => return Self::failed(id, "baseline_failed", &e),
            };
            for name in names {
                let test = format!("{}::{name}", target.file);
                let one = Instant::now();
                let ran = match project.run(target, std::slice::from_ref(&name)) {
                    Ok(ran) => ran,
                    Err(e) => return Self::failed(id, "baseline_failed", &e),
                };
                tests.push(Timed {
                    test: test.clone(),
                    file: target.file.clone(),
                    duration_ms: millis(one),
                });
                match ran.results.iter().find(|r| r.name == name) {
                    Some(r) if r.passed => {}
                    Some(r) => failures.push(Failure {
                        test: test.clone(),
                        message: r.message.clone(),
                    }),
                    None => failures.push(Failure {
                        test: test.clone(),
                        message: "the test did not report a result".to_string(),
                    }),
                }
                known.insert(test, (index, name));
            }
        }
        self.session.tests = known;
        Step::Reply(protocol::baseline_done(
            id,
            millis(started),
            &tests,
            &failures,
        ))
    }

    fn cast(&mut self, id: i64, wekufe: &str, site: &Value, tests: &[String]) -> Step {
        let Some(project) = self.project.as_mut() else {
            return Self::not_ready(id, "`hello` has to come first");
        };
        if self.session.tests.is_empty() {
            return Self::not_ready(id, "`baseline` has to come first");
        }
        // Group the tests by the executable that holds them, keeping order.
        let mut groups: Vec<(usize, Vec<(String, String)>)> = Vec::new();
        for test in tests {
            let Some((target, name)) = self.session.tests.get(test) else {
                return Step::Reply(protocol::error(
                    id,
                    "unknown_test",
                    &format!("`{test}` is not a test this kalku knows"),
                    false,
                ));
            };
            match groups.iter_mut().find(|(t, _)| t == target) {
                Some((_, names)) => names.push((test.clone(), name.clone())),
                None => groups.push((*target, vec![(test.clone(), name.clone())])),
            }
        }
        let bad = |why: &str| Step::Reply(protocol::error(id, "bad_request", why, false));
        let (Some(file), Some(from), Some(to), Some(replacement)) = (
            site["file"].as_str(),
            site["span"]["start"]["byte"].as_u64(),
            site["span"]["end"]["byte"].as_u64(),
            site["replacement"].as_str(),
        ) else {
            return bad("the site has no file, span or replacement");
        };
        let (from, to) = (from as usize, to as usize);
        let original = match project.read(file) {
            Ok(text) => text,
            Err(e) => return bad(&format!("cannot read `{file}` in the reni: {e}")),
        };
        // A site is only valid for the text it was found in.
        if site["original"].as_str() != original.get(from..to) {
            return bad(&format!("`{file}` is not the text this site was found in"));
        }
        let spliced = crate::source::Source::new(&original).splice(from, to, replacement);

        let started = Instant::now();
        if let Err(e) = project.write(file, &spliced) {
            return bad(&format!("cannot write `{file}` in the reni: {e}"));
        }
        let outcome = cast_in(project.as_mut(), &self.session.targets, &groups);
        // Whatever happened, the next cast starts from the original.
        if let Err(e) = project.write(file, &original) {
            return Step::Reply(protocol::error(
                id,
                "load_failed",
                &format!("could not restore `{file}` in the reni: {e}"),
                true,
            ));
        }
        match outcome {
            Ok(outcome) => Step::Reply(protocol::cast_done(id, wekufe, &outcome, millis(started))),
            Err(Trouble::Unknown(test)) => Step::Reply(protocol::error(
                id,
                "unknown_test",
                &format!("`{test}` ran but reported no result"),
                false,
            )),
            Err(Trouble::Io(e)) => Self::failed(id, "cast_failed", &e),
        }
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

fn millis(since: Instant) -> u64 {
    since.elapsed().as_millis() as u64
}

enum Trouble {
    /// A test that was asked for and never reported.
    Unknown(String),
    Io(std::io::Error),
}

// Build the spliced project, then run the groups until one fails. A test
// that did not pass is what kills; so is one that never reported, since a
// process that aborted is not a test that passed.
fn cast_in(
    project: &mut dyn Project,
    targets: &[Target],
    groups: &[(usize, Vec<(String, String)>)],
) -> Result<Outcome, Trouble> {
    match project.build().map_err(Trouble::Io)? {
        Build::Failed(message) => return Ok(Outcome::CompileError { message }),
        Build::Built(_) => {}
    }
    for (target, tests) in groups {
        let names: Vec<String> = tests.iter().map(|(_, name)| name.clone()).collect();
        let ran = project
            .run(&targets[*target], &names)
            .map_err(Trouble::Io)?;
        let passed = |name: &str| ran.results.iter().any(|r| r.name == name && r.passed);
        let failed = |name: &str| ran.results.iter().any(|r| r.name == name && !r.passed);
        if let Some((id, _)) = tests.iter().find(|(_, name)| failed(name)) {
            return Ok(Outcome::Killed { by: id.clone() });
        }
        if let Some((id, _)) = tests.iter().find(|(_, name)| !passed(name)) {
            if ran.success {
                return Err(Trouble::Unknown(id.clone()));
            }
            return Ok(Outcome::Killed { by: id.clone() });
        }
    }
    Ok(Outcome::Survived)
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
