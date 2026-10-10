//! Answeran.results.iter().find(|r| &r.name == name)ing requests.
//!
//! One request per line in, one reply per line out, and nothing else may
//! reach stdout: anything a toolchain prints goes to stderr, because the
//! kaikai side banishes a worker for a line it cannot read.

use crate::cancel::Cancel;
use crate::coverage::{self, Entry, Tools, invert};
use crate::editions::{self, Cargo, Editions};
use crate::project::{Build, Cargo as CargoProject, Project, Target};
use crate::protocol::{
    self, Coverage, DecodeError, Failure, Hello, Outcome, Request, SitesRequest, Skipped, Timed,
};
use crate::sites;
use crate::toolchain::{self, Toolchain};
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Instant;

/// What to do with a reply.
#[derive(Debug, PartialEq, Eq)]
pub enum Step {
    /// Say this and carry on.
    Reply(String),
    /// Say this and leave.
    Stop(String),
    /// Say nothing: the request was a cast that was aborted, and an aborted
    /// cast gets no answer of its own.
    Quiet,
}

/// Asks the compiler which toolchain this is.
type Probe = Box<dyn Fn() -> Result<Toolchain, String>>;

/// Builds the answer to "which edition is this file?" for a project root.
type ChooseEditions = Box<dyn FnMut(&Path) -> Box<dyn Editions>>;

/// Builds the project a `hello` names, to be built and run in its reni.
type ChooseProject = Box<dyn FnMut(&Hello) -> Box<dyn Project>>;

/// What `prepare` learned, which `baseline` and `cast` run against. Every
/// worker is prepared and only one is asked for a baseline, so a cast has
/// to work from what `prepare` alone left behind.
#[derive(Default)]
struct Session {
    targets: Vec<Target>,
    // Every test in the order the targets list them: its id, the target
    // that holds it, and its own name.
    listed: Vec<(String, usize, String)>,
    // A test's id to its place in `listed`.
    tests: HashMap<String, usize>,
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
    cancel: Cancel,
    tools: Option<Tools>,
    // Whether the last cast left the reni as it found it.
    restored: bool,
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
                    hello.worker,
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
            cancel: Cancel::new(),
            tools: None,
            restored: true,
        }
    }

    /// Where an `abort` is raised while a cast is running.
    pub fn cancel(&self) -> Cancel {
        self.cancel.clone()
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
            Request::Abort { cast } => Step::Reply(protocol::aborted(id, cast, self.restored)),
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
        self.tools = toolchain
            .sysroot
            .as_deref()
            .and_then(|sysroot| coverage::find_tools(sysroot, &toolchain.host));
        let mut capabilities = vec!["cast", "abort"];
        if self.tools.is_some() {
            capabilities.push("per_test_coverage");
        }
        Step::Reply(protocol::ready(id, &self.adapter, &runtime, &capabilities))
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
                let mut listed = Vec::new();
                for (index, target) in targets.iter().enumerate() {
                    match project.list(target) {
                        Ok(names) => listed.extend(
                            names
                                .into_iter()
                                .map(|n| (format!("{}::{n}", target.file), index, n)),
                        ),
                        Err(e) => {
                            return Self::failed(
                                id,
                                "prepare_failed",
                                &format!("cannot list tests: {e}"),
                            );
                        }
                    }
                }
                let tests = listed
                    .iter()
                    .enumerate()
                    .map(|(at, (test, _, _))| (test.clone(), at))
                    .collect();
                self.session = Session {
                    targets,
                    listed,
                    tests,
                };
                Step::Reply(protocol::prepared(id, millis(started), modules))
            }
        }
    }

    // Every test is run alone once, which is how it gets a duration of its
    // own: stable libtest does not time them. With the LLVM tools at hand the
    // run is on an instrumented build and each test also says which lines it
    // reached, which is what lets a cast run only the tests that matter.
    fn baseline(&mut self, id: i64) -> Step {
        let (Some(project), Some(hello)) = (self.project.as_mut(), self.hello.as_ref()) else {
            return Self::not_ready(id, "`hello` has to come first");
        };
        if self.session.targets.is_empty() {
            return Self::not_ready(id, "`prepare` has to come first");
        }
        let started = Instant::now();
        if let Some(tools) = &self.tools {
            match project.instrument(tools) {
                Ok(Build::Built(_)) => {}
                Ok(Build::Failed(why)) => {
                    return Self::failed(id, "baseline_failed", &why);
                }
                Err(e) => return Self::failed(id, "baseline_failed", &e),
            }
        }
        let covering = self.tools.is_some();
        let (mut tests, mut failures) = (Vec::new(), Vec::new());
        let mut reached = Vec::new();
        for (test, index, name) in &self.session.listed {
            let target = &self.session.targets[*index];
            let one = Instant::now();
            let ran = if covering {
                project.run_covered(target, name).map(|(ran, lines)| {
                    reached.push((test.clone(), lines));
                    ran
                })
            } else {
                project.run(target, std::slice::from_ref(name))
            };
            let ran = match ran {
                Ok(ran) => ran,
                Err(e) => return Self::failed(id, "baseline_failed", &e),
            };
            tests.push(Timed {
                test: test.clone(),
                file: target.file.clone(),
                duration_ms: millis(one),
            });
            match ran.results.iter().find(|r| r.name == *name) {
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
        }
        let coverage = if covering {
            match spoken(&invert(&reached), hello) {
                Ok(c) => Some(c),
                Err(e) => return Self::failed(id, "baseline_failed", &e),
            }
        } else {
            None
        };
        Step::Reply(protocol::baseline_done(
            id,
            millis(started),
            &tests,
            &failures,
            coverage.as_ref(),
            &project.differences(),
        ))
    }

    fn cast(&mut self, id: i64, wekufe: &str, site: &Value, tests: &[String]) -> Step {
        let Some(project) = self.project.as_mut() else {
            return Self::not_ready(id, "`hello` has to come first");
        };
        if self.session.targets.is_empty() {
            return Self::not_ready(id, "`prepare` has to come first");
        }
        // Group the tests by the executable that holds them, keeping order.
        let mut groups: Vec<(usize, Vec<(String, String)>)> = Vec::new();
        for test in tests {
            let Some((_, target, name)) = self
                .session
                .tests
                .get(test)
                .map(|at| &self.session.listed[*at])
            else {
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

        // Asked to stop before it began: nothing was touched.
        if self.cancel.asked(id) {
            return Step::Quiet;
        }
        let started = Instant::now();
        if let Err(e) = project.write(file, &spliced) {
            return bad(&format!("cannot write `{file}` in the reni: {e}"));
        }
        let cancel = self.cancel.clone();
        project.set_stop(Some(Arc::new(move || cancel.asked(id))));
        let outcome = cast_in(project.as_mut(), &self.session.targets, &groups);
        project.set_stop(None);
        let aborted =
            matches!(&outcome, Err(Trouble::Io(e)) if e.kind() == std::io::ErrorKind::Interrupted);
        // Whatever happened, the next cast starts from the original.
        let restore = project.write(file, &original);
        self.restored = restore.is_ok();
        if let (Err(e), false) = (&restore, aborted) {
            return Step::Reply(protocol::error(
                id,
                "load_failed",
                &format!("could not restore `{file}` in the reni: {e}"),
                true,
            ));
        }
        match outcome {
            Err(Trouble::Io(_)) if aborted => Step::Quiet,
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

// What a baseline says about coverage: inline when it is small, and in a file
// in the reni when it is not, which is every project big enough to be worth
// measuring. The kaikai side reads it from there.
fn spoken(entries: &[Entry], hello: &Hello) -> std::io::Result<Coverage> {
    let lines: Vec<String> = entries
        .iter()
        .map(|e| protocol::entry_json(e).to_string())
        .collect();
    let size: usize = lines.iter().map(|l| l.len() + 1).sum();
    if size as i64 <= hello.inline_limit_bytes {
        return Ok(Coverage::Inline(entries.to_vec()));
    }
    let dir = Path::new(&hello.reni).join("coverage");
    std::fs::create_dir_all(&dir)?;
    let path = dir.join(format!("{}.json", hello.worker));
    std::fs::write(&path, format!("[{}]\n", lines.join(",\n")))?;
    Ok(Coverage::Path(path.to_string_lossy().into_owned()))
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::project::{Ran, Result_};
    use serde_json::json;
    use std::cell::RefCell;
    use std::collections::VecDeque;
    use std::fs;
    use std::io;
    use std::rc::Rc;

    // What the fake project was asked, shared with the test that built it.
    #[derive(Default)]
    struct Seen {
        runs: Vec<(String, Vec<String>)>,
        writes: Vec<(String, String)>,
    }

    // What running the named tests of a target says.
    type Running = Box<dyn FnMut(&Target, &[String]) -> io::Result<Ran>>;

    struct Fake {
        seen: Rc<RefCell<Seen>>,
        files: HashMap<String, String>,
        copy_fails: bool,
        builds: VecDeque<io::Result<Build>>,
        targets: Vec<Target>,
        listing: HashMap<String, Vec<String>>,
        list_fails: bool,
        ran: Running,
        // The write that fails, counting from one.
        write_fails_at: Option<usize>,
        unreadable: bool,
        // What instrumenting says, and the lines each test reached.
        instrument: Option<io::Result<Build>>,
        reached: HashMap<String, crate::coverage::Lines>,
        // How it says its run of the suite differs.
        unlike: Vec<String>,
    }

    fn io_error(what: &str) -> io::Error {
        io::Error::other(what.to_string())
    }

    fn target(file: &str) -> Target {
        Target {
            package: "p".into(),
            select: vec!["--lib".into()],
            file: file.into(),
        }
    }

    fn pass(name: &str) -> Result_ {
        Result_ {
            name: name.into(),
            passed: true,
            message: String::new(),
        }
    }

    fn fail(name: &str, message: &str) -> Result_ {
        Result_ {
            name: name.into(),
            passed: false,
            message: message.into(),
        }
    }

    impl Fake {
        fn new() -> (Fake, Rc<RefCell<Seen>>) {
            let seen = Rc::new(RefCell::new(Seen::default()));
            let fake = Fake {
                seen: seen.clone(),
                files: HashMap::from([("src/lib.rs".to_string(), "a >= 1".to_string())]),
                copy_fails: false,
                builds: VecDeque::new(),
                targets: vec![target("src/lib.rs")],
                listing: HashMap::from([(
                    "src/lib.rs".to_string(),
                    vec!["t::one".to_string(), "t::two".to_string()],
                )]),
                list_fails: false,
                ran: Box::new(|_, names| {
                    Ok(Ran {
                        results: names.iter().map(|n| pass(n)).collect(),
                        success: true,
                    })
                }),
                write_fails_at: None,
                unreadable: false,
                instrument: None,
                reached: HashMap::new(),
                unlike: Vec::new(),
            };
            (fake, seen)
        }
    }

    impl Project for Fake {
        fn copy(&mut self) -> io::Result<()> {
            if self.copy_fails {
                Err(io_error("disk full"))
            } else {
                Ok(())
            }
        }

        fn build(&mut self) -> io::Result<Build> {
            self.builds
                .pop_front()
                .unwrap_or_else(|| Ok(Build::Built(self.targets.clone())))
        }

        fn list(&mut self, target: &Target) -> io::Result<Vec<String>> {
            if self.list_fails {
                return Err(io_error("cannot list"));
            }
            Ok(self.listing.get(&target.file).cloned().unwrap_or_default())
        }

        fn run(&mut self, target: &Target, names: &[String]) -> io::Result<Ran> {
            self.seen
                .borrow_mut()
                .runs
                .push((target.file.clone(), names.to_vec()));
            (self.ran)(target, names)
        }

        fn read(&self, file: &str) -> io::Result<String> {
            if self.unreadable {
                return Err(io_error("gone"));
            }
            self.files
                .get(file)
                .cloned()
                .ok_or_else(|| io_error("no such file"))
        }

        fn instrument(&mut self, _tools: &Tools) -> io::Result<Build> {
            self.instrument
                .take()
                .unwrap_or_else(|| Ok(Build::Built(self.targets.clone())))
        }

        fn run_covered(
            &mut self,
            target: &Target,
            name: &str,
        ) -> io::Result<(Ran, crate::coverage::Lines)> {
            let ran = self.run(target, std::slice::from_ref(&name.to_string()))?;
            Ok((ran, self.reached.get(name).cloned().unwrap_or_default()))
        }

        fn differences(&self) -> Vec<String> {
            self.unlike.clone()
        }

        fn write(&mut self, file: &str, text: &str) -> io::Result<()> {
            let mut seen = self.seen.borrow_mut();
            seen.writes.push((file.to_string(), text.to_string()));
            if self.write_fails_at == Some(seen.writes.len()) {
                return Err(io_error("read-only"));
            }
            Ok(())
        }
    }

    struct Fixed(Result<Option<String>, String>);

    impl Editions for Fixed {
        fn of(&mut self, _file: &Path) -> Result<Option<String>, String> {
            self.0.clone()
        }
    }

    fn service_with(fake: Fake, edition: Result<Option<String>, String>) -> Service {
        let mut fake = Some(fake);
        let mut edition = Some(edition);
        Service::with(
            "0.0.0",
            Box::new(|| {
                Ok(Toolchain {
                    version_line: "rustc 1.98.1".into(),
                    release: (1, 98, 1),
                    host: "h".into(),
                    sysroot: None,
                })
            }),
            Box::new(move |_| Box::new(Fixed(edition.take().unwrap_or(Ok(None))))),
            Box::new(move |_| Box::new(fake.take().expect("one project per hello"))),
        )
    }

    fn greeted(fake: Fake) -> Service {
        greeted_in(fake, "/nowhere", Ok(Some("2024".into())))
    }

    fn greeted_in(fake: Fake, root: &str, edition: Result<Option<String>, String>) -> Service {
        let mut service = service_with(fake, edition);
        let said = say(
            &mut service,
            json!({"type": "hello", "id": 1, "protocol": 1, "root": root,
                "reni": "/reni", "worker": 0, "inline_limit_bytes": 1, "env": {}}),
        );
        assert_eq!(said["type"], "ready", "{said}");
        service
    }

    fn say(service: &mut Service, request: serde_json::Value) -> serde_json::Value {
        match service.handle(&request.to_string()) {
            Step::Reply(r) | Step::Stop(r) => serde_json::from_str(&r).unwrap(),
            Step::Quiet => serde_json::Value::Null,
        }
    }

    fn site(original: &str, replacement: &str) -> serde_json::Value {
        json!({"site_id": "abc", "file": "src/lib.rs", "ordinal": 1,
            "span": {"start": {"line": 1, "col": 3, "byte": 2},
                     "end": {"line": 1, "col": 5, "byte": 4}},
            "spell": "compare", "original": original, "replacement": replacement,
            "reload": "module"})
    }

    fn cast(service: &mut Service, site: serde_json::Value, tests: &[&str]) -> serde_json::Value {
        say(
            service,
            json!({"type": "cast", "id": 9, "wekufe": "abc", "site": site, "tests": tests}),
        )
    }

    fn prepared_and_baselined(fake: Fake) -> Service {
        let mut service = greeted(fake);
        assert_eq!(
            say(&mut service, json!({"type": "prepare", "id": 2}))["type"],
            "prepared"
        );
        service
    }

    #[test]
    fn every_request_is_named_by_its_wire_name() {
        let hello = Hello {
            protocol: 1,
            root: String::new(),
            reni: String::new(),
            worker: 0,
            inline_limit_bytes: 0,
            env: vec![],
        };
        let sites = SitesRequest {
            files: vec![],
            spells: vec![],
            exclude_calls: vec![],
        };
        let names = [
            (Request::Hello(hello), "hello"),
            (Request::Prepare, "prepare"),
            (Request::Baseline, "baseline"),
            (Request::Sites(sites), "sites"),
            (
                Request::Cast {
                    wekufe: String::new(),
                    site: json!({}),
                    tests: vec![],
                },
                "cast",
            ),
            (Request::Abort { cast: 1 }, "abort"),
            (Request::Reset, "reset"),
            (Request::Reload { files: vec![] }, "reload"),
            (Request::Delegate, "delegate"),
            (Request::Shutdown, "shutdown"),
        ];
        for (request, wire) in names {
            assert_eq!(name(&request), wire);
        }
    }

    #[test]
    fn what_it_does_not_answer_is_a_fatal_error_naming_the_request() {
        let (fake, _) = Fake::new();
        let mut service = greeted(fake);

        let said = say(&mut service, json!({"type": "reset", "id": 4}));

        assert_eq!(said["code"], "not_implemented");
        assert_eq!(said["fatal"], true);
        assert!(said["message"].as_str().unwrap().contains("`reset`"));
    }

    #[test]
    fn nothing_but_hello_works_before_hello() {
        let (fake, _) = Fake::new();
        let mut service = service_with(fake, Ok(Some("2024".into())));

        for request in [
            json!({"type": "prepare", "id": 2}),
            json!({"type": "baseline", "id": 3}),
            json!({"type": "sites", "id": 4, "files": [], "spells": [], "exclude_calls": []}),
            json!({"type": "cast", "id": 5, "wekufe": "a", "site": site("a", "b"), "tests": []}),
        ] {
            let said = say(&mut service, request);
            assert_eq!(said["code"], "not_ready", "{said}");
            assert_eq!(said["fatal"], false);
            assert_eq!(said["message"], "`hello` has to come first");
        }
    }

    #[test]
    fn baseline_and_cast_need_a_prepare_first() {
        let (fake, _) = Fake::new();
        let mut service = greeted(fake);

        let baseline = say(&mut service, json!({"type": "baseline", "id": 3}));
        let cast_ = cast(&mut service, site(">=", ">"), &["x"]);

        for said in [baseline, cast_] {
            assert_eq!(said["code"], "not_ready");
            assert_eq!(said["fatal"], false);
            assert_eq!(said["message"], "`prepare` has to come first");
        }
    }

    #[test]
    fn a_prepare_that_cannot_finish_says_why_and_is_fatal() {
        type Setup = fn(&mut Fake);
        let cases: [(Setup, &str); 4] = [
            (
                |f| f.copy_fails = true,
                "cannot copy the project: disk full",
            ),
            (
                |f| f.builds.push_back(Err(io_error("no cargo"))),
                "cannot run cargo: no cargo",
            ),
            (
                |f| f.builds.push_back(Ok(Build::Failed("error[E0308]".into()))),
                "error[E0308]",
            ),
            (|f| f.list_fails = true, "cannot list tests: cannot list"),
        ];
        for (setup, message) in cases {
            let (mut fake, _) = Fake::new();
            setup(&mut fake);
            let mut service = greeted(fake);

            let said = say(&mut service, json!({"type": "prepare", "id": 2}));

            assert_eq!(said["code"], "prepare_failed", "{said}");
            assert_eq!(said["fatal"], true);
            assert_eq!(said["message"], message);
        }
    }

    #[test]
    fn a_prepare_counts_the_executables_it_built() {
        let (mut fake, _) = Fake::new();
        fake.targets = vec![target("src/lib.rs"), target("tests/pipe.rs")];
        let mut service = greeted(fake);

        let said = say(&mut service, json!({"type": "prepare", "id": 2}));

        assert_eq!(said["modules"], 2);
    }

    #[test]
    fn a_baseline_lists_every_test_with_its_file_and_a_green_status() {
        let (fake, _) = Fake::new();
        let mut service = prepared_and_baselined(fake);

        let said = say(&mut service, json!({"type": "baseline", "id": 3}));

        assert_eq!(said["status"], "green");
        assert_eq!(said["failures"], json!([]));
        let tests: Vec<_> = said["tests"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| (t["test"].as_str().unwrap(), t["file"].as_str().unwrap()))
            .collect();
        assert_eq!(
            tests,
            [
                ("src/lib.rs::t::one", "src/lib.rs"),
                ("src/lib.rs::t::two", "src/lib.rs")
            ]
        );
    }

    #[test]
    fn a_baseline_says_how_the_project_says_its_run_differed() {
        let (mut fake, _) = Fake::new();
        fake.unlike = vec!["it ran in a copy".to_string()];
        let mut service = prepared_and_baselined(fake);

        let said = say(&mut service, json!({"type": "baseline", "id": 3}));

        assert_eq!(said["differences"], json!(["it ran in a copy"]));
    }

    #[test]
    fn a_baseline_is_red_with_the_message_of_each_failing_test() {
        let (mut fake, _) = Fake::new();
        fake.ran = Box::new(|_, names| {
            let name = &names[0];
            Ok(Ran {
                results: if name == "t::one" {
                    vec![fail(name, "assertion failed")]
                } else if name == "t::two" {
                    vec![]
                } else {
                    vec![pass(name)]
                },
                success: false,
            })
        });
        let mut service = prepared_and_baselined(fake);

        let said = say(&mut service, json!({"type": "baseline", "id": 3}));

        assert_eq!(said["status"], "red");
        assert_eq!(
            said["failures"],
            json!([
                {"test": "src/lib.rs::t::one", "message": "assertion failed"},
                {"test": "src/lib.rs::t::two", "message": "the test did not report a result"}
            ])
        );
    }

    #[test]
    fn a_baseline_that_cannot_run_a_test_is_fatal() {
        let (mut fake, _) = Fake::new();
        fake.ran = Box::new(|_, _| Err(io_error("spawn failed")));
        let mut service = prepared_and_baselined(fake);

        let said = say(&mut service, json!({"type": "baseline", "id": 3}));

        assert_eq!(said["code"], "baseline_failed");
        assert_eq!(said["fatal"], true);
        assert_eq!(said["message"], "spawn failed");
    }

    #[test]
    fn a_cast_splices_the_site_runs_the_tests_and_restores_the_file() {
        let (mut fake, seen) = Fake::new();
        fake.ran = Box::new(|_, names| {
            Ok(Ran {
                results: vec![fail(&names[0], "boom")],
                success: false,
            })
        });
        let mut service = prepared_and_baselined(fake);

        let said = cast(&mut service, site(">=", ">"), &["src/lib.rs::t::one"]);

        assert_eq!(said["outcome"], "killed", "{said}");
        assert_eq!(said["killed_by"], "src/lib.rs::t::one");
        let seen = seen.borrow();
        assert_eq!(
            seen.writes,
            [
                ("src/lib.rs".to_string(), "a > 1".to_string()),
                ("src/lib.rs".to_string(), "a >= 1".to_string())
            ]
        );
    }

    #[test]
    fn tests_of_one_executable_run_together_and_in_the_order_asked() {
        let (mut fake, seen) = Fake::new();
        fake.targets = vec![target("src/lib.rs"), target("tests/pipe.rs")];
        fake.listing
            .insert("tests/pipe.rs".into(), vec!["p::three".into()]);
        let mut service = prepared_and_baselined(fake);

        let said = cast(
            &mut service,
            site(">=", ">"),
            &[
                "src/lib.rs::t::two",
                "tests/pipe.rs::p::three",
                "src/lib.rs::t::one",
            ],
        );

        assert_eq!(said["outcome"], "survived", "{said}");
        assert_eq!(
            seen.borrow().runs,
            [
                (
                    "src/lib.rs".to_string(),
                    vec!["t::two".to_string(), "t::one".to_string()]
                ),
                ("tests/pipe.rs".to_string(), vec!["p::three".to_string()])
            ]
        );
    }

    #[test]
    fn a_wekufe_that_does_not_compile_is_a_compile_error_with_the_message() {
        let (mut fake, _) = Fake::new();
        fake.builds
            .push_back(Ok(Build::Built(vec![target("src/lib.rs")])));
        fake.builds
            .push_back(Ok(Build::Failed("expected `;`".into())));
        let mut service = prepared_and_baselined(fake);

        let said = cast(&mut service, site(">=", ">"), &["src/lib.rs::t::one"]);

        assert_eq!(said["outcome"], "compile_error");
        assert_eq!(said["message"], "expected `;`");
        assert_eq!(said["dirty"], false);
    }

    // A test process that aborted is not a test that passed.
    #[test]
    fn a_test_that_never_reported_in_a_failed_run_is_a_kill() {
        let (mut fake, _) = Fake::new();
        fake.ran = Box::new(|_, _| {
            Ok(Ran {
                results: vec![],
                success: false,
            })
        });
        let mut service = prepared_and_baselined(fake);

        let said = cast(&mut service, site(">=", ">"), &["src/lib.rs::t::two"]);

        assert_eq!(said["outcome"], "killed");
        assert_eq!(said["killed_by"], "src/lib.rs::t::two");
    }

    // A clean run that left a test out says nothing about that test.
    #[test]
    fn a_test_that_never_reported_in_a_clean_run_is_an_error_not_a_survivor() {
        let (mut fake, _) = Fake::new();
        fake.ran = Box::new(|_, _| {
            Ok(Ran {
                results: vec![],
                success: true,
            })
        });
        let mut service = prepared_and_baselined(fake);

        let said = cast(&mut service, site(">=", ">"), &["src/lib.rs::t::two"]);

        assert_eq!(said["code"], "unknown_test");
        assert_eq!(said["fatal"], false);
    }

    #[test]
    fn a_test_the_baseline_never_listed_is_unknown() {
        let (fake, _) = Fake::new();
        let mut service = prepared_and_baselined(fake);

        let said = cast(&mut service, site(">=", ">"), &["src/lib.rs::nope"]);

        assert_eq!(said["code"], "unknown_test");
        assert_eq!(said["fatal"], false);
        assert!(
            said["message"]
                .as_str()
                .unwrap()
                .contains("src/lib.rs::nope")
        );
    }

    #[test]
    fn a_site_that_does_not_say_where_it_is_is_refused() {
        let (fake, _) = Fake::new();
        let mut service = prepared_and_baselined(fake);
        let mut headless = site(">=", ">");
        headless["span"] = json!({});

        let said = cast(&mut service, headless, &["src/lib.rs::t::one"]);

        assert_eq!(said["code"], "bad_request");
        assert_eq!(said["fatal"], false);
    }

    #[test]
    fn a_file_the_reni_cannot_read_or_a_site_from_other_text_is_refused() {
        let (mut fake, seen) = Fake::new();
        fake.unreadable = true;
        let mut service = prepared_and_baselined(fake);
        let said = cast(&mut service, site(">=", ">"), &["src/lib.rs::t::one"]);
        assert_eq!(said["code"], "bad_request");
        assert!(said["message"].as_str().unwrap().contains("cannot read"));

        let (fake, seen2) = Fake::new();
        let mut service = prepared_and_baselined(fake);
        let said = cast(&mut service, site("<=", "<"), &["src/lib.rs::t::one"]);
        assert_eq!(said["code"], "bad_request");
        assert!(said["message"].as_str().unwrap().contains("not the text"));
        // Nothing was written for a site that did not fit.
        assert!(seen.borrow().writes.is_empty());
        assert!(seen2.borrow().writes.is_empty());
    }

    #[test]
    fn a_file_that_cannot_be_written_is_refused_and_nothing_runs() {
        let (mut fake, seen) = Fake::new();
        fake.write_fails_at = Some(1);
        let mut service = prepared_and_baselined(fake);

        let said = cast(&mut service, site(">=", ">"), &["src/lib.rs::t::one"]);

        assert_eq!(said["code"], "bad_request");
        assert!(said["message"].as_str().unwrap().contains("cannot write"));
        assert!(seen.borrow().runs.is_empty());
    }

    #[test]
    fn a_file_that_cannot_be_restored_is_fatal() {
        let (mut fake, _) = Fake::new();
        fake.write_fails_at = Some(2);
        let mut service = prepared_and_baselined(fake);

        let said = cast(&mut service, site(">=", ">"), &["src/lib.rs::t::one"]);

        assert_eq!(said["code"], "load_failed");
        assert_eq!(said["fatal"], true);
        assert!(said["message"].as_str().unwrap().contains("restore"));
    }

    #[test]
    fn a_cast_that_cannot_run_its_tests_is_a_failure_of_the_cast() {
        let (mut fake, _) = Fake::new();
        fake.ran = Box::new(|_, _| Err(io_error("spawn failed")));
        let mut service = prepared_and_baselined(fake);

        let said = cast(&mut service, site(">=", ">"), &["src/lib.rs::t::one"]);

        assert_eq!(said["code"], "cast_failed");
        assert_eq!(said["fatal"], true);
    }

    fn sites_of(service: &mut Service, files: &[&str]) -> serde_json::Value {
        say(
            service,
            json!({"type": "sites", "id": 4, "files": files,
                "spells": ["compare"], "exclude_calls": []}),
        )
    }

    fn skipped(said: &serde_json::Value) -> Vec<(String, String)> {
        said["skipped"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| {
                (
                    s["file"].as_str().unwrap().to_string(),
                    s["reason"].as_str().unwrap().to_string(),
                )
            })
            .collect()
    }

    #[test]
    fn a_file_is_skipped_with_the_reason_it_cannot_be_searched() {
        let dir = std::env::temp_dir().join(format!("kalku-svc-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("src")).unwrap();
        fs::write(
            dir.join("src/ok.rs"),
            "pub fn f(a: i32) -> bool { a >= 1 }\n",
        )
        .unwrap();
        fs::write(dir.join("src/broken.rs"), "fn f( {\n").unwrap();
        let root = dir.to_str().unwrap();
        let (fake, _) = Fake::new();
        let mut service = greeted_in(fake, root, Ok(Some("2024".into())));

        let said = sites_of(
            &mut service,
            &["README.md", "src/ok.rs", "src/missing.rs", "src/broken.rs"],
        );

        assert_eq!(said["type"], "sites_found");
        assert_eq!(said["sites"].as_array().unwrap().len(), 1);
        assert_eq!(
            skipped(&said),
            [
                ("README.md".to_string(), "not_rust".to_string()),
                ("src/missing.rs".to_string(), "unreadable".to_string()),
                ("src/broken.rs".to_string(), "parse_error".to_string()),
            ]
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_file_in_no_package_or_an_old_edition_is_skipped_by_name() {
        let (fake, _) = Fake::new();
        let mut service = greeted_in(fake, "/nowhere", Ok(None));
        let said = sites_of(&mut service, &["src/a.rs"]);
        assert_eq!(
            skipped(&said),
            [("src/a.rs".to_string(), "not_in_a_package".to_string())]
        );

        let (fake, _) = Fake::new();
        let mut service = greeted_in(fake, "/nowhere", Ok(Some("2021".into())));
        let said = sites_of(&mut service, &["src/a.rs"]);
        assert_eq!(
            skipped(&said),
            [("src/a.rs".to_string(), "unsupported_edition".to_string())]
        );
    }

    #[test]
    fn a_project_cargo_cannot_read_is_a_fatal_error_not_a_skip() {
        let (fake, _) = Fake::new();
        let mut service = greeted_in(fake, "/nowhere", Err("no Cargo.toml".into()));

        let said = sites_of(&mut service, &["src/a.rs"]);

        assert_eq!(said["code"], "not_a_cargo_project");
        assert_eq!(said["fatal"], true);
        assert_eq!(said["message"], "no Cargo.toml");
    }

    #[test]
    fn a_toolchain_that_is_missing_or_too_old_refuses_the_hello() {
        let hello = json!({"type": "hello", "id": 1, "protocol": 1, "root": "/",
            "reni": "/r", "worker": 0, "inline_limit_bytes": 1, "env": {}});
        let probe = |release: Result<(u32, u32, u32), String>| -> Probe {
            Box::new(move || {
                release.clone().map(|release| Toolchain {
                    version_line: "rustc old".into(),
                    release,
                    host: "h".into(),
                    sysroot: None,
                })
            })
        };
        let make = |p: Probe| {
            Service::with(
                "0",
                p,
                Box::new(|_| Box::new(Fixed(Ok(None)))),
                Box::new(|_| Box::new(Fake::new().0)),
            )
        };

        let missing = say(&mut make(probe(Err("no rustc".into()))), hello.clone());
        let old = say(&mut make(probe(Ok((1, 84, 0)))), hello.clone());
        let mismatch = say(
            &mut make(probe(Ok((1, 98, 1)))),
            json!({"type": "hello", "id": 1, "protocol": 2, "root": "/",
                "reni": "/r", "worker": 0, "inline_limit_bytes": 1, "env": {}}),
        );

        assert_eq!(missing["code"], "toolchain_missing");
        assert_eq!(old["code"], "unsupported_toolchain");
        assert_eq!(mismatch["code"], "protocol_mismatch");
        for said in [missing, old, mismatch] {
            assert_eq!(said["fatal"], true);
        }
    }

    #[test]
    fn a_garbled_line_is_refused_and_a_shutdown_stops() {
        let (fake, _) = Fake::new();
        let mut service = greeted(fake);

        let refused = match service.handle("not json") {
            Step::Reply(r) => serde_json::from_str::<serde_json::Value>(&r).unwrap(),
            Step::Stop(_) | Step::Quiet => panic!("a garbled line must not stop the kalku"),
        };
        let stopped = service.handle(r#"{"type":"shutdown","id":7}"#);

        assert_eq!(refused["code"], "bad_request");
        assert_eq!(refused["fatal"], false);
        assert!(matches!(stopped, Step::Stop(r) if r.contains("\"bye\"")));
    }

    fn interrupted() -> io::Error {
        io::Error::new(io::ErrorKind::Interrupted, "aborted")
    }

    fn abort(service: &mut Service, cast: i64) -> serde_json::Value {
        say(service, json!({"type": "abort", "id": 20, "cast": cast}))
    }

    #[test]
    fn an_abort_with_nothing_running_says_the_reni_is_as_it_was() {
        let (fake, _) = Fake::new();
        let mut service = prepared_and_baselined(fake);

        let said = abort(&mut service, 5);

        assert_eq!(said["type"], "aborted");
        assert_eq!(said["id"], 20);
        assert_eq!(said["cast"], 5);
        assert_eq!(said["restored"], true);
    }

    // The aborted cast gets no answer of its own; the abort does.
    #[test]
    fn a_cast_that_is_stopped_says_nothing_and_leaves_the_file_as_it_was() {
        let (mut fake, seen) = Fake::new();
        fake.ran = Box::new(|_, _| Err(interrupted()));
        let mut service = prepared_and_baselined(fake);

        let quiet = service.handle(
            &json!({"type": "cast", "id": 9, "wekufe": "abc", "site": site(">=", ">"),
                "tests": ["src/lib.rs::t::one"]})
            .to_string(),
        );

        assert!(matches!(quiet, Step::Quiet));
        let writes = seen.borrow().writes.clone();
        assert_eq!(
            writes,
            [
                ("src/lib.rs".to_string(), "a > 1".to_string()),
                ("src/lib.rs".to_string(), "a >= 1".to_string())
            ]
        );
        assert_eq!(abort(&mut service, 9)["restored"], true);
    }

    #[test]
    fn a_cast_asked_to_stop_before_it_began_touches_nothing() {
        let (fake, seen) = Fake::new();
        let mut service = prepared_and_baselined(fake);
        service.cancel().ask(9);

        let quiet = service.handle(
            &json!({"type": "cast", "id": 9, "wekufe": "abc", "site": site(">=", ">"),
                "tests": ["src/lib.rs::t::one"]})
            .to_string(),
        );

        assert!(matches!(quiet, Step::Quiet));
        assert!(seen.borrow().writes.is_empty());
        assert!(seen.borrow().runs.is_empty());
    }

    // A stop that cannot put the file back is the one thing the kaikai side
    // must hear, so that it recycles this kalku.
    #[test]
    fn a_stopped_cast_that_cannot_restore_its_file_says_so_when_aborted() {
        let (mut fake, _) = Fake::new();
        fake.ran = Box::new(|_, _| Err(interrupted()));
        fake.write_fails_at = Some(2);
        let mut service = prepared_and_baselined(fake);

        let quiet = service.handle(
            &json!({"type": "cast", "id": 9, "wekufe": "abc", "site": site(">=", ">"),
                "tests": ["src/lib.rs::t::one"]})
            .to_string(),
        );

        assert!(matches!(quiet, Step::Quiet));
        assert_eq!(abort(&mut service, 9)["restored"], false);
    }

    #[test]
    fn a_cast_that_finishes_restores_and_reports_the_reni_clean() {
        let (fake, _) = Fake::new();
        let mut service = prepared_and_baselined(fake);

        cast(&mut service, site(">=", ">"), &["src/lib.rs::t::one"]);

        assert_eq!(abort(&mut service, 9)["restored"], true);
    }

    // A toolchain directory that has the two programs, which is all the
    // service asks of it: they are only run by the real project.
    fn toolchain_with_tools() -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("kalku-svc-tools-{}", std::process::id()));
        let bin = dir.join("lib/rustlib/h/bin");
        fs::create_dir_all(&bin).unwrap();
        fs::write(bin.join("llvm-profdata"), "").unwrap();
        fs::write(bin.join("llvm-cov"), "").unwrap();
        dir
    }

    fn greeted_with_tools(fake: Fake, inline_limit: i64, reni: &str) -> Service {
        let sysroot = toolchain_with_tools();
        let mut fake = Some(fake);
        let mut service = Service::with(
            "0.0.0",
            Box::new(move || {
                Ok(Toolchain {
                    version_line: "rustc 1.98.1".into(),
                    release: (1, 98, 1),
                    host: "h".into(),
                    sysroot: Some(sysroot.clone()),
                })
            }),
            Box::new(|_| Box::new(Fixed(Ok(Some("2024".into()))))),
            Box::new(move |_| Box::new(fake.take().unwrap())),
        );
        let ready = say(
            &mut service,
            json!({"type": "hello", "id": 1, "protocol": 1, "root": "/nowhere",
                "reni": reni, "worker": 4, "inline_limit_bytes": inline_limit, "env": {}}),
        );
        assert_eq!(
            ready["capabilities"],
            json!(["cast", "abort", "per_test_coverage"])
        );
        assert_eq!(
            say(&mut service, json!({"type": "prepare", "id": 2}))["type"],
            "prepared"
        );
        service
    }

    fn lines(file: &str, numbers: &[u32]) -> crate::coverage::Lines {
        crate::coverage::Lines::from([(file.to_string(), numbers.iter().copied().collect())])
    }

    #[test]
    fn coverage_is_claimed_only_where_the_tools_are() {
        let (fake, _) = Fake::new();
        let mut service = service_with(fake, Ok(Some("2024".into())));

        let ready = say(
            &mut service,
            json!({"type": "hello", "id": 1, "protocol": 1, "root": "/",
                "reni": "/r", "worker": 0, "inline_limit_bytes": 1, "env": {}}),
        );

        assert_eq!(ready["capabilities"], json!(["cast", "abort"]));
    }

    #[test]
    fn a_baseline_says_which_tests_reached_which_lines() {
        let (mut fake, _) = Fake::new();
        fake.reached
            .insert("t::one".into(), lines("src/lib.rs", &[1, 2]));
        fake.reached
            .insert("t::two".into(), lines("src/lib.rs", &[2, 9]));
        let mut service = greeted_with_tools(fake, 1 << 20, "/unused");

        let said = say(&mut service, json!({"type": "baseline", "id": 3}));

        assert_eq!(said["status"], "green", "{said}");
        assert!(said.get("coverage_path").is_none());
        assert_eq!(
            said["coverage"],
            json!([
                {"file": "src/lib.rs", "line": 1, "tests": ["src/lib.rs::t::one"]},
                {"file": "src/lib.rs", "line": 2, "tests": ["src/lib.rs::t::one", "src/lib.rs::t::two"]},
                {"file": "src/lib.rs", "line": 9, "tests": ["src/lib.rs::t::two"]}
            ])
        );
    }

    #[test]
    fn a_map_too_big_for_a_line_goes_to_a_file_in_the_reni() {
        let reni = std::env::temp_dir().join(format!("kalku-svc-reni-{}", std::process::id()));
        let _ = fs::remove_dir_all(&reni);
        let (mut fake, _) = Fake::new();
        fake.reached
            .insert("t::one".into(), lines("src/lib.rs", &[1, 2, 3]));
        let mut service = greeted_with_tools(fake, 10, reni.to_str().unwrap());

        let said = say(&mut service, json!({"type": "baseline", "id": 3}));

        assert!(said.get("coverage").is_none(), "{said}");
        let path = said["coverage_path"].as_str().unwrap();
        assert_eq!(path, reni.join("coverage/4.json").to_str().unwrap());
        let written: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap();
        assert_eq!(written.as_array().unwrap().len(), 3);
        assert_eq!(
            written[0],
            json!({"file": "src/lib.rs", "line": 1, "tests": ["src/lib.rs::t::one"]})
        );
        let _ = fs::remove_dir_all(&reni);
    }

    #[test]
    fn an_instrumented_build_that_fails_stops_the_baseline_with_its_words() {
        let (mut fake, _) = Fake::new();
        fake.instrument = Some(Ok(Build::Failed("error: linking".into())));
        let mut service = greeted_with_tools(fake, 1 << 20, "/unused");

        let said = say(&mut service, json!({"type": "baseline", "id": 3}));

        assert_eq!(said["code"], "baseline_failed");
        assert_eq!(said["fatal"], true);
        assert_eq!(said["message"], "error: linking");

        let (mut fake, _) = Fake::new();
        fake.instrument = Some(Err(io_error("no cargo")));
        let mut service = greeted_with_tools(fake, 1 << 20, "/unused");
        let said = say(&mut service, json!({"type": "baseline", "id": 3}));
        assert_eq!(said["message"], "no cargo");
    }

    #[test]
    fn a_test_that_cannot_be_run_for_coverage_stops_the_baseline() {
        let (mut fake, _) = Fake::new();
        fake.ran = Box::new(|_, _| Err(io_error("spawn failed")));
        let mut service = greeted_with_tools(fake, 1 << 20, "/unused");

        let said = say(&mut service, json!({"type": "baseline", "id": 3}));

        assert_eq!(said["code"], "baseline_failed");
        assert_eq!(said["message"], "spawn failed");
    }
}
