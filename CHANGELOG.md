## v0.10.0 (2026-10-08)

### Added

- a project nobody set up is measured, and left as it was (#235)
- **elixir**: a project that does not depend on the kalku is measured, and left as it was (#234)

### Fixed

- a kalku that writes a great deal during one wait no longer ends the run (#242)

### Changed

- a wait holds only the last lines a kalku wrote (#243)

## v0.9.3 (2026-10-07)

### Fixed

- **elixir**: a test whose module cannot be set up is not a test that passed (#223)
- **elixir**: a failing suite is refused without measuring its coverage first (#220)
- **elixir**: what the project prints no longer ends the run (#216)
- **elixir**: an application that logs while it starts no longer ends the run (#215)

### Changed

- **elixir**: per-test coverage costs what the tests cost, not the suite squared (#221)

## v0.9.2 (2026-10-06)

### Fixed

- **elixir**: a line run through a mocked module is reported under its own file (#213)
- **elixir**: a wekufe is no longer hidden by a test that mocks its module (#208)
- **elixir**: a wekufe in a module the suite mocks no longer outlives its cast (#206)
- **elixir**: a test that drives a mock no longer kills a wekufe it never reaches (#205)
- **elixir**: a test that sets a mocking library up in its own setup no longer ends the run (#201)

## v0.9.1 (2026-10-06)

### Fixed

- **orchestrator**: a kalku that says a great deal no longer ends the run (#197)

## v0.9.0 (2026-10-06)

### Added

- **java**: projects that test with JUnit 4 or with TestNG (#188)
- **java**: Gradle builds, asked by running them (#187)
- **java**: Maven projects of several modules (#184)
- **java**: per-test coverage, counted by JaCoCo and read away from the tests (#183)
- **java**: a cast that hangs is aborted in place (#182)

### Fixed

- **java**: a module that is nothing but tests does not stop a constant from being cast (#185)
- **java**: a real project builds in the reni (#181)

## v0.8.0 (2026-10-05)

### Added

- **java**: `kalku init` knows a Maven project, and the Java kalku ships in the release (#178)
- **java**: prepare, baseline and cast for a Maven project (#177)
- **java**: a native Java kalku that finds sites (#176)

## v0.7.0 (2026-10-05)

### Added

- **elixir**: the Elixir kalku runs on Elixir 1.16 and OTP 26 (#171)

### Fixed

- **elixir**: a suite that mocks under cover leaves no file in the project (#173)

## v0.6.2 (2026-10-05)

### Fixed

- **mcp**: a run through the agent's tools is written to the ledger (#168)
- **agent**: a survivor carries the first five covering tests and their count (#169)

## v0.6.1 (2026-10-04)

### Fixed

- **orchestrator**: a run that stops short says so, a healthy kalku's last words kill nothing, and an interrupt reaches the run (#156)

## v0.6.0 (2026-10-04)

### Added

- **orchestrator**: hold a cast to a memory ceiling, and end a killed kalku's whole tree (#152)

## v0.5.1 (2026-10-04)

### Fixed

- **measure**: a re-cast of a wekufe whose file has changed says so (#146)
- **measure**: a red baseline says why each test failed, and where it ran (#147)
- **python**: a failure's message is the line that says what went wrong (#148)
- **init**: the Python summoner names kalku-python when it is not on PATH (#144)
- **python**: sites lost to a form feed, to a U+2028, and to a parenthesised case (#142)

## v0.5.0 (2026-10-03)

### Added

- **python**: a native Python kalku (#135)

### Fixed

- **core**: the files of a change and of a project are the language's own (#134)

## v0.4.0 (2026-10-03)

### Added

- **rust**: per-test coverage with LLVM, so a cast runs only the tests that reach it (#128)
- **rust**: the Rust kalku answers abort (#127)
- **rust**: kalku init sets up a Cargo project, and the Rust kalku ships in the release (#122)
- **rust**: the Rust kalku prepares, measures and casts in the reni (#119)
- **rust**: a native Rust kalku that finds sites (#118)
- **gate**: block a change on the holes it introduced (#117)

### Fixed

- **elixir**: the suite's after_suite hooks do not run between the runs of a warm runtime (#131)
- **report**: a file the kalku would not search is in the report, and the gate hears of it (#130)
- **orchestrator**: say when no file could be searched for sites (#129)
- **rust**: sync the reni's copy by content, so a build is never trusted over other code (#124)
- **self-mutate**: build the scripted kalku and allow a cast the time a suite takes (#121)
- **self-mutate**: measure with the pinned release, in a copy of the tree (#120)

## v0.3.0 (2026-10-01)

### Added

- **gate**: apply the floor a project configured, and refuse a partial one (#115)
- **orchestrator**: say that a wait is still a wait, and quote the kalku (#111)
- **cli**: let a person answer a proposed equivalent (#110)
- **report**: say once when many survivors share a call (#107)

### Fixed

- **orchestrator**: kill what a kalku started, and verify that it died (#99)
- **elixir**: withhold coverage this kalku cannot stand behind (#109)
- **init**: give the summoning a contract, so a fix to it reaches projects (#108)
- **report**: a run that measured part of a file says so before its score (#112)
- **init**: let the run say where the reni is, instead of the script (#105)

## v0.2.0 (2026-09-29)

### Added

- **scheduler**: say how far a run has got, to whoever is listening (#98)
- **protocol**: tell a client what a run is about to cost (#95)
- **server**: a fiber for each client, and a session that answers while it measures (#90)

### Fixed

- **server**: measure what the client asked for, and refuse what is not served (#94)

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
