## v0.30.0 (2026-10-30)

### Added

- **report**: name the run's log in the JSON report and the agent summary (#521)
- **serve**: tell a client which cast is taking long, and where the run's log is (#520)

## v0.29.0 (2026-10-29)

### Added

- **run**: keep a log of every run in the reni, with each kalku's stderr (#514)
- **run**: say how far a run has got by default, and name a cast that is taking long (#513)

### Fixed

- **typescript**: leave out by design a file the tsconfig.json does not include (#509)

## v0.28.0 (2026-10-29)

### Added

- **java**: cast the return spell (#486)

### Fixed

- **init**: add the Elixir dependency when deps is written with parentheses (#507)
- **java**: call keeps a call in the body of a try with a catch (#506)
- a site is reached by the tests of any line of its text (#505)
- a file a kalku leaves out by design does not block the gate (#503)
- **cpp**: a wekufe in a file no compile reads is no_coverage (#502)
- **rust**: a wekufe in a file no build compiles is no_coverage (#501)
- **java**: leave returned literals to the literal spell (#499)
- **typescript**: leave returned literals to the literal spell (#498)
- **typescript**: give no return site to a function that declares no type (#497)
- **rust**: leave returned literals to the literal spell (#492)
- **rust**: read a should_panic test by its own name (#491)
- **python**: leave a returned True or False to literal (#488)

## v0.27.0 (2026-10-27)

### Added

- **cpp**: cast the return spell (#484)
- **typescript**: cast the return spell (#483)
- **elixir**: cast the return spell (#479)
- **rust**: cast the return spell (#482)
- **go**: cast the return spell (#480)
- **python**: cast the return spell (#478)
- add the return spell to the catalogue, cast only where a project names it (#476)

### Fixed

- **python**: keep the numbers of inherited descriptors taken in the child (#481)
- **elixir**: propose deleting a function clause only when arm is asked for (#477)

## v0.26.1 (2026-10-26)

### Fixed

- **java**: let the workers' Maven builds take turns (#470)
- **java**: say why coverage was withheld in baseline_done (#472)
- **java**: let a class be first used by a test when counting coverage (#471)
- **java**: compile a cast with what its annotation processors depend on (#466)

### Changed

- **java**: send per-test coverage packed when it does not fit on a line (#467)
- **java**: run a cast's tests in a JVM that stays (#469)

## v0.26.0 (2026-10-26)

### Added

- **java**: answer reload by compiling what was edited into the copy (#464)
- **rust**: answer reload by copying the changed files and building again (#463)
- **python**: answer reload by updating the copy and collecting again (#462)

## v0.25.1 (2026-10-25)

### Changed

- **elixir**: purge the compiler's own modules after each compile (#460)

## v0.25.0 (2026-10-25)

### Added

- **report**: name the redundant tests (#451)
- **report**: name the tests that kill no wekufe (#447)
- **report**: name the tests the casting time goes to (#446)

### Fixed

- **typescript**: count coverage under Vite 6.4, which reads nothing outside the project (#458)
- **baseline**: a suite with no test in it cannot measure (#456)
- **rust**: copy a directory that holds the crate when its build reads beside it (#457)
- **rust**: find the tests of a library built as several crate types (#455)
- **cli**: kalku cast keeps and reuses the baseline (#442)
- **server**: runs served to a client or an agent keep and reuse the baseline (#440)
- **go**: leave what git and kalku keep out of a package's fingerprint (#439)

### Changed

- **watch**: reload a warm pool after an edit instead of summoning another (#450)
- **cli**: say every refusal one way in main (#435)
- write pipes instead of the method form (#434)

## v0.24.0 (2026-10-19)

### Added

- **cpp**: a baseline that runs only what changed (#432)
- **cpp**: credit each test with the lines it ran (#430)
- **cpp**: judge a Catch2 or doctest executable case by case where CTest runs it whole (#423)

### Changed

- **cpp**: share one build across workers under Ninja too (#431)
- **cpp**: link a test again only when what it links into itself changed (#427)
- **cpp**: build the project once and copy it to every worker under make (#426)

## v0.23.0 (2026-10-18)

### Added

- **c**: measure C projects with the C and C++ kalku (#421)

## v0.22.0 (2026-10-18)

### Added

- **cpp**: a C++ kalku (#415)
- **kaikai**: a baseline re-runs only the test modules that read what changed (#414)

### Changed

- pass constructors as functions instead of wrapping them in lambdas (#412)

## v0.21.0 (2026-10-18)

### Added

- **cli**: kalku merge combines sharded reports into one (#408)
- **cli**: kalku run --watch re-runs on every save (#404)

### Fixed

- **orchestrator**: a run an interrupt cut short exits 2, not 0 (#407)

## v0.20.0 (2026-10-17)

### Added

- **java**: a baseline runs only the test classes that changed (#399)
- **rust**: a baseline runs only the packages that changed (#398)
- **kaikai**: a baseline runs only when the package changed (#397)
- **python**: a baseline runs only the test files that changed (#396)

## v0.19.0 (2026-10-17)

### Added

- **typescript**: a baseline runs only the test files that changed (#392)
- **typescript**: coverage per test (#391)
- **typescript**: a TypeScript kalku (#388)

### Fixed

- **typescript**: tests that share their titles are credited with the coverage either ran (#394)
- **typescript**: a removed call leaves `{}`, and a typeof guard's type name is not a site (#393)

## v0.18.2 (2026-10-17)

### Fixed

- **measure**: a wekufe a kalku lists twice is cast once, and one name for two wekufe ends the run (#385)

## v0.18.1 (2026-10-17)

### Fixed

- **kaikai**: a baseline with thousands of covered lines no longer overflows the kalku's fiber (#382)
- **elixir**: a wekufe that breaks ExUnit leaves the runtime dirty, not every later cast crashed (#381)

## v0.18.0 (2026-10-16)

### Added

- **elixir**: a baseline runs only the test modules that changed (#377)
- **go**: a baseline runs only the packages that changed (#370)
- **protocol**: a baseline reuses the tests whose fingerprint has not changed (#369)

### Fixed

- **settling**: key a reni by the project's path, not its folder's name (#368)
- **costing**: count the rest of the suite when coverage is doubted (#367)

## v0.17.0 (2026-10-15)

### Added

- **kaikai**: per-test coverage from kai test --coverage (#359)

## v0.16.0 (2026-10-14)

### Added

- **go**: per-test coverage from Go's cover tool (#354)
- **go**: a native Go kalku (#348)

## v0.15.0 (2026-10-14)

### Added

- **kaikai**: a project can judge wekufe with its own oracle (#337)

### Fixed

- **elixir**: a reset restarts an application that registers handlers as it starts (#343)
- **mcp**: a server nobody speaks to gives up, and the servers refuse arguments (#346)
- **orchestrator**: a wait for a kalku's reply runs in constant stack (#340)

### Changed

- **kaikai**: the kalku that ran the baseline goes on to cast (#339)

## v0.14.7 (2026-10-13)

### Fixed

- **server**: the report of a run that stopped short says so (#334)
- **init**: the requirement init writes starts at the binary's own minor (#333)
- **protocol**: a kalku says `crashed` of a cast it watched end with no verdict (#331)
- **cli**: every command answers `--help` instead of running (#332)
- **scheduler**: the summons a run allows are counted in a row (#330)
- a run that stops short reports what it cast (#328)
- `kalku accept` writes an entry the next run reads (#327)

### Changed

- **server**: the server asks kaikai for its uid, not /usr/bin/id (#326)

## v0.14.6 (2026-10-13)

### Changed

- what stood in for a kaikai gap that is closed goes away (#323)

## v0.14.5 (2026-10-13)

### Fixed

- **kaikai**: the kaikai kalku ends with its run, and ends what it started (#320)

### Changed

- the kaikai side is written the way the linter asks (#314)

## v0.14.4 (2026-10-12)

### Changed

- the kalku that read the project is the first to cast it (#308)

## v0.14.3 (2026-10-10)

### Fixed

- **python**: a program a test starts in a session of its own ends with the run (#300)

## v0.14.2 (2026-10-10)

### Fixed

- **rust**: the kalku ends with its run, and ends what it started (#298)
- the Java and Python kalku end with their run, and end what they started (#296)

## v0.14.1 (2026-10-10)

### Fixed

- **java**: every test source directory a build declares is run (#293)

## v0.14.0 (2026-10-10)

### Added

- **protocol**: coverage a kalku doubts still chooses the tests, and the rest judge a survivor (#289)
- the ratchet holds a run of the whole project to a stored baseline (#286)
- `kalku run --all` measures the whole project, and `--shard i/n` a part of it (#285)

### Fixed

- **java**: every source directory a build declares is measured (#290)
- **elixir**: code that runs as the application starts is measured (#288)
- **java**: the tests a build includes and excludes are the tests that run (#287)
- the hint of an `arm` or `negate` survivor no longer says no test reached it (#284)

## v0.13.0 (2026-10-10)

### Added

- **protocol**: a kalku that withholds per-test coverage says why, and the report carries it (#271)

### Fixed

- the Python, Rust and Java kalku say how their run of the suite differs (#280)
- **rust**: a test the project marks `#[ignore]` is not reported as failing (#279)
- **elixir**: a wekufe no test ran is not reported as a survivor (#277)
- **java**: a project with both JUnit 4 and TestNG has the tests of both run (#276)
- **config**: `ratchet = true` is refused while nothing stores a baseline (#272)
- a wekufe proved equivalent by its code no longer makes a run read as partial (#275)
- a line a kalku writes that is not a message is quoted, and said to be from stdout (#270)
- **elixir**: text that is not ASCII crosses the protocol as it is written (#268)

## v0.12.0 (2026-10-09)

### Added

- **mcp**: kalku_run takes since and limit, and a host that leaves ends the run (#264)

### Fixed

- **mcp**: a long run is heard, a call that is called off stops, and the server ends on SIGTERM (#265)
- **elixir**: a kalku whose run is gone ends, and ends what it started (#263)

## v0.11.0 (2026-10-09)

### Added

- a run goes on without the tests that fail before anything is cast, and names them (#260)
- **elixir**: a suite with failing tests is measured for coverage, when some of it passes (#259)

### Fixed

- a survivor the whole suite judged is not reported as covered by it (#253)
- the refusal of a failing suite says how the run differed, in the kalku's own words (#249)
- **elixir**: rebar3's own cache goes into the reni, not under the project's dependencies (#252)
- **elixir**: a path dependency built as the kalku starts does not write on the channel (#251)
- **elixir**: a site is judged by every test that calls its function (#247)
- **elixir**: a run of the tests that ExUnit did not finish is answered, not died of (#245)

### Changed

- **elixir**: coverage names each test once, and credits a call only with what lines miss (#258)
- planning a large suite costs its size, not its size squared for every site (#256)

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
