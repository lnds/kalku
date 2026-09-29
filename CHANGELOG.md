## v0.1.4 (2026-09-29)

### Added

- **run**: say what a run is about to cost, before it spends it (#80)

### Fixed

- **core**: let what a project declared reach the run that measures it (#84)
- **elixir**: say why a kalku died, and stop corrupting the channel (#87)

### Changed

- **ci**: stop rebuilding what nothing changed, and answer the cheap questions first (#89)

## v0.1.3 (2026-09-29)

### Added

- **init**: declare the dependency instead of asking for it (#76)

### Fixed

- **elixir**: measure a real project, and use the coverage it measures (#78)
- **release**: keep the two halves in step, and say which one to move (#77)

## v0.1.2 (2026-09-28)

### Fixed

- **ci**: build the Linux release where kaikai can run (#74)

## v0.1.1 (2026-09-28)

## v0.1.0 (2026-09-28)

### Added

- **elixir**: publish the Elixir kalku as a Hex package
- **server**: keep a project's kalku warm between runs (#71)
- **server**: stream a run as it happens, not when it is over (#70)
- **spells**: `await` and `supervise`, for the part of a suite nobody looks at (#68)
- **mcp**: `kalku mcp`, the agent tools where an agent already is (#66)
- **server**: `kalku serve`, the client protocol on a local socket (#65)
- **cli**: the agent loop — formats, cast, show, propose-equivalent (#63)
- **cli**: `kalku info`, so nobody has to guess what kalku means (#62)
- **cli**: `kalku init` sets a project up, and `kalku run` needs nothing else (#59)
- **orchestrator**: a run end to end, so kalku can measure kalku (#52)
- **kaikai**: the kalku kalku measures itself with (#51)
- **core**: a project's session and the keys that make a run warm (#49)
- **core**: the report an agent can act on, and loud suppressions (#48)
- **elixir**: reload changed files, and the modules stitched into them (#47)
- **elixir**: reset in place, and tell the truth about dirty state (#46)
- **elixir**: abort a cast in place, keeping the kalku warm (#45)
- **elixir**: cast a wekufe into the warm runtime and report what it came to (#44)
- **elixir**: per-test coverage, measured one test at a time (#43)
- **elixir**: baseline, the suite run once in the kalku's own runtime (#41)
- **elixir**: prepare, compiling into the reni and nowhere else (#38)
- **orchestrator**: what to do about a kalku, decided without touching one (#30)
- **orchestrator**: one kalku as a process, and a kalku that does what a script says (#28)
- **core**: read .kalku.toml (#26)
- **core**: the pure decision-making of the kaikai side (#24)
- **wire**: the kalku and client protocols in kaikai (#22)
- **elixir**: protocol loop and sites for all six spells (#14)

### Fixed

- **elixir**: report coverage relative to the project, however the root was named (#69)
- **orchestrator**: keep a kalku's last words, and quote them where they belong (#61)
- **elixir**: attribute coverage to the tests that run a line, and use it (#60)
- **kaikai**: a run that stopped is not a wekufe that survived (#57)
- **elixir**: never report a wekufe as survived when nothing judged it (#55)
- **elixir**: ship the summoning as an executable, not as prose (#39)
- **orchestrator**: banish every kalku on every path out of a run (#35)
- **wire**: read lines up to the length the protocol states (#32)

### Changed

- **protocol**: drop the evidence field from cast_done (#21)
