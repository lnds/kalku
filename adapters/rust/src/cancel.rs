//! Stopping a cast that the kaikai side has given up on.
//!
//! The kaikai side enforces the timeout from outside and says `abort` while a
//! cast is running. The kalku is busy with that cast, so the line is read by
//! another thread, which raises this flag; the cast looks at it between the
//! moments it waits on cargo, and stops what it started.

use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicI64, Ordering};
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
    let mut child = command.spawn()?;
    let mut pipe = child.stdout.take().expect("stdout was piped");
    let reader = thread::spawn(move || {
        let mut bytes = Vec::new();
        let _ = pipe.read_to_end(&mut bytes);
        bytes
    });
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break Some(status);
        }
        if stop.is_some_and(|s| s()) {
            kill_group(&mut child);
            break None;
        }
        thread::sleep(POLL);
    };
    let stdout = reader.join().unwrap_or_default();
    match status {
        Some(status) => Ok(Captured { stdout, status }),
        None => Err(io::Error::new(io::ErrorKind::Interrupted, "aborted")),
    }
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
