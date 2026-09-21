---
paths:
  - "docs/protocol.md"
  - "docs/protocol/**"
  - "kalku/wire/**"
  - "adapters/*/lib/**/protocol*"
---

# Changing the protocols

The kalku protocol is the contract that lets the core stay language-agnostic; the client protocol is what editors and CI build on. Treat both as public API.

- **Spec first.** `docs/protocol.md` is authoritative. Change the spec in the same PR as the code, never after.
- **Versioned.** The handshake carries a protocol version. Adding optional fields is compatible; renaming, removing, or changing the meaning of a field is a version bump.
- **Transport-agnostic.** Messages are newline-delimited JSON and must work identically over stdio and over a socket. No transport-specific fields.
- **Language-agnostic.** A field that only makes sense for one language is a smell: find the general concept or keep it inside the kalku.
- **Outcomes a native kalku cannot observe** (`timeout`, `crashed`) are decided by the kaikai side. Only a driver kalku reports them, relaying its framework's status in `tool_status`.
- **Scores are computed only by the kaikai side.** A driver never forwards a framework's score.
- **Every message has a fixture** under `docs/protocol/fixtures/`, validated by the kaikai side's tests and by each kalku's tests.
