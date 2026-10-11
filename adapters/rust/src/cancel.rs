//! Stopping a cast that the kaikai side has given up on.
//!
//! The kaikai side enforces the timeout from outside and says `abort` while a
//! cast is running. The kalku is busy with that cast, so the line is read by
//! another thread, which raises this flag; the cast looks at it between the
//! moments it waits on cargo, and stops what it started.
//!
//! The same thread hears the input end with no `shutdown` on it, which is a
//! run that is gone: then every command under way is killed and the kalku
//! ends, whatever request it was serving.

use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::thread;
use std::time::Duration;
use std::{io, io::Read};

const NONE: i64 = i64::MIN;

/// Which cast the kaikai side asked to stop, if any; shared between the
/// thread that reads requests and the one that serves them.
#[derive(Clone)]
pub struct Cancel(Arc<AtomicI64>);

impl Cancel {
    pub fn new() -> Self {
        Cancel(Arc::new(AtomicI64::new(NONE)))
    }

    /// The kaikai side asked for this cast to stop.
    pub fn ask(&self, cast: i64) {
        self.0.store(cast, Ordering::SeqCst);
    }

    /// Whether this cast was asked to stop.
    pub fn asked(&self, cast: i64) -> bool {
        self.0.load(Ordering::SeqCst) == cast
    }
}

impl Default for Cancel {
    fn default() -> Self {
        Cancel::new()
    }
}

/// What a running operation looks at to know it should stop.
pub type Stop = Arc<dyn Fn() -> bool + Send + Sync>;

/// What a command wrote on stdout, and how it ended.
pub struct Captured {
    pub stdout: Vec<u8>,
    pub status: ExitStatus,
}

const POLL: Duration = Duration::from_millis(15);

/// Run a command to its end and keep its stdout, unless `stop` says so first:
/// then the command and everything it started are killed and the answer is an
/// `Interrupted` error. The command gets a process group of its own, because
/// a test binary is a grandchild of cargo and killing cargo alone would leave
/// it running.
pub fn capture(mut command: Command, stop: Option<&Stop>) -> io::Result<Captured> {
    use std::os::unix::process::CommandExt;
    command.stdout(Stdio::piped()).process_group(0);
    let mut child = {
        let mut groups = started();
        let child = command.spawn()?;
        groups.push(child.id() as libc::pid_t);
        child
    };
    let mut pipe = child.stdout.take().expect("stdout was piped");
    let reader = thread::spawn(move || {
        let mut bytes = Vec::new();
        let _ = pipe.read_to_end(&mut bytes);
        bytes
    });
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Ok(status),
            Ok(None) => {}
            Err(e) => break Err(e),
        }
        if stop.is_some_and(|s| s()) {
            kill_group(&mut child);
            break Err(io::Error::new(io::ErrorKind::Interrupted, "aborted"));
        }
        thread::sleep(POLL);
    };
    let group = child.id() as libc::pid_t;
    started().retain(|g| *g != group);
    let stdout = reader.join().unwrap_or_default();
    Ok(Captured {
        stdout,
        status: status?,
    })
}

// The process groups of the commands under way.
static STARTED: Mutex<Vec<libc::pid_t>> = Mutex::new(Vec::new());

fn started() -> MutexGuard<'static, Vec<libc::pid_t>> {
    STARTED.lock().unwrap_or_else(PoisonError::into_inner)
}

/// End every command under way, with everything it started, and then this
/// process. It is for when the run that summoned the kalku is gone: nobody
/// is left to read an answer, so nothing is finished first.
pub fn leave(code: i32) -> ! {
    // Held to the end, so that no command starts after the ones under way
    // were killed.
    let groups = started();
    for group in groups.iter() {
        // SAFETY: `killpg` takes a process group id and a signal and touches
        // nothing of ours; each group is one a command was started in.
        unsafe {
            libc::killpg(*group, libc::SIGKILL);
        }
    }
    std::process::exit(code)
}

fn kill_group(child: &mut Child) {
    // SAFETY: `killpg` takes a process group id and a signal and touches
    // nothing of ours; the group is the one this command was started in.
    unsafe {
        libc::killpg(child.id() as libc::pid_t, libc::SIGKILL);
    }
    let _ = child.kill();
    let _ = child.wait();
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicBool;
    use std::time::Instant;

    // A process that was killed and not yet reaped by its new parent is a
    // zombie: it still answers signal 0, but it is not running.
    fn running(pid: i32) -> bool {
        let out = Command::new("ps")
            .args(["-o", "stat=", "-p", &pid.to_string()])
            .output()
            .unwrap();
        let state = String::from_utf8_lossy(&out.stdout);
        let state = state.trim();
        !state.is_empty() && !state.starts_with('Z')
    }

    fn shell(script: &str) -> Command {
        let mut c = Command::new("sh");
        c.arg("-c").arg(script);
        c
    }

    #[test]
    fn a_cast_is_asked_to_stop_by_its_own_id_only() {
        let cancel = Cancel::new();
        assert!(!cancel.asked(1));

        cancel.ask(7);

        assert!(cancel.asked(7));
        assert!(!cancel.asked(8));
        assert!(!Cancel::default().asked(7));
    }

    #[test]
    fn a_command_that_ends_is_captured_with_its_output_and_status() {
        let said = capture(shell("printf hello; exit 3"), None).unwrap();

        assert_eq!(said.stdout, b"hello");
        assert_eq!(said.status.code(), Some(3));
    }

    #[test]
    fn a_command_is_killed_with_what_it_started_when_asked_to_stop() {
        let dir = std::env::temp_dir().join(format!("kalku-cancel-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let pidfile = dir.join("pid");
        // A shell that starts a sleeper of its own and waits for it: the
        // sleeper is the grandchild that a kill of the shell alone would miss.
        let script = format!("sleep 60 & echo $! > {}; wait", pidfile.display());
        let raised = Arc::new(AtomicBool::new(false));
        let flag = raised.clone();
        let stop: Stop = Arc::new(move || flag.load(Ordering::SeqCst));
        let trip = raised.clone();
        let started = Instant::now();
        let waiter = thread::spawn(move || {
            thread::sleep(Duration::from_millis(400));
            trip.store(true, Ordering::SeqCst);
        });

        let outcome = capture(shell(&script), Some(&stop));
        waiter.join().unwrap();

        assert_eq!(
            outcome.err().map(|e| e.kind()),
            Some(io::ErrorKind::Interrupted)
        );
        assert!(started.elapsed() < Duration::from_secs(20));
        let pid: i32 = std::fs::read_to_string(&pidfile)
            .unwrap()
            .trim()
            .parse()
            .unwrap();
        // The kill is a signal, and the process takes a moment to go.
        let deadline = Instant::now() + Duration::from_secs(10);
        while running(pid) && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(50));
        }
        assert!(!running(pid), "the grandchild outlived the abort");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
