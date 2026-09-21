# Execution isolation and the server surface

Casting wekufe executes modified code by design. Treat every part that casts or accepts connections as security-sensitive.

## The user's project

- Wekufe never touch the user's working tree: they are cast in memory, and all artefacts live in the reni.
- Cleanup must survive crashes: a killed server or kalku leaves nothing behind in the project.
- Every cast has a timeout. A wekufe that loops forever is a `timeout` outcome, not a hang.

## The server

- A server that casts wekufe on request is remote code execution by definition.
- Default bind: a Unix socket with `0600` permissions, or `127.0.0.1` with a per-session token. Never `0.0.0.0` by default.
- Remote kalku, remote clients, or farm mode require explicit opt-in, sandboxing, and real authentication; do not add them as a convenience flag.

## Kalku

- Kalku are supervised, and a warm kalku is valuable: a hung cast is first aborted in place (`abort`), dirty state is first reset in place (`reset`). Killing the process is the last step, when the kalku stops answering; the wekufe it was casting is reported, not lost.
- A kalku's environment is explicit: pass only what the test run needs.
