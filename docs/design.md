# kalku — design

kalku measures how good a test suite is by breaking the code on purpose and checking whether the tests notice. It is **native mutation testing where none exists** — Elixir first — **and one honest gate over the tools that already exist** elsewhere, built for three kinds of client: **humans, CI, and coding agents**. Agents write tests fast; kalku tells them whether those tests test anything. It is built as a kaikai orchestrator driving small per-language kalku, and it runs on its own sources.

## Glossary

In Mapuche belief, a *kalku* is a sorcerer who works in a hidden cave, the *reni*, and commands the *wekufe*, malign spirits that bring harm. kalku borrows that cast. The vocabulary is fixed; use these words and no others.

| Term | Meaning |
|---|---|
| **kaikai side** / **orchestrator** | The core, written in kaikai. Plans, schedules, caches, scores, reports. Runs as the CLI or the server. |
| **kalku** | A worker process bound to one language: the adapter running inside a warm runtime (for Elixir, a BEAM node with the project loaded). A kalku knows its language's dark arts and nothing else. |
| **reni** | A project's isolated workspace: build artefacts, caches, per-worker databases. Mutants live and die here, never in the user's tree. |
| **wekufe** | A mutant: the original program with exactly one defect cast into it. |
| **kalkutun** / **spell** | A mutation operator: a class of defect a kalku can cast (`arm`, `compare`, …). *Kalkutun* is the Mapuche word for the harm a kalku works; the two are synonyms in prose, and code and protocol say `spell`. |
| **site** | A place in the source where a spell applies: file, token span, spell, replacement text. |
| **summon** / **banish** | Start / stop a kalku. |

Outcomes (`killed`, `survived`, `timeout`, …) keep plain technical names: they appear in CI logs and must read without the glossary. A surviving wekufe is a hole in the suite, with a file, a line, and a diff.

## Goals

1. **Trustworthy results.** A wekufe differs from its parent by one defect and nothing else. Only a failing test counts as a kill. Survivors come first; the score is secondary.
2. **Never harm the user's project.** No wekufe is ever written to the working tree.
3. **Fast enough to run on every change.** Minutes on a first run, seconds on incremental ones.
4. **Language-agnostic core.** A new language costs one kalku, not a fork of the orchestrator.
5. **Self-hosted.** kalku measures its own suite with its own kaikai kalku.
6. **Agents are first-class clients.** A coding agent can find a survivor, write a test, and learn in seconds whether it killed the wekufe — without being able to hide holes instead of closing them.

## Non-goals

- Replacing a language's existing property-testing libraries (e.g. StreamData). Property testing arrives later as *synthesis* of properties from declared laws, not as a new generator framework.
- Parsing target languages in kaikai.
- Remote execution of untrusted repositories (see *Later: farm mode*).

## Architecture

```
            clients                        kaikai side                          kalku
  ┌──────────────────────┐      ┌──────────────────────────────┐       ┌──────────────────┐
  │ kalku CLI            │      │ session (one per project)    │       │ kalku[elixir] #1 │
  │ kalku run --watch    │─────►│  ├─ planner                  │──────►│ kalku[elixir] #2 │
  │ editor / LSP / MCP   │ NDJSON│  ├─ scheduler + supervisor  │ NDJSON│ ...              │
  │ CI / coding agents   │◄─────│  ├─ cache                    │◄──────│ kalku[elixir] #N │
  └──────────────────────┘      │  └─ scorer + reporter        │       └──────────────────┘
                                └──────────────────────────────┘               │
                                               │                               ▼
                                               └────────────── reni (per project, isolated)
```

- **Clients** speak the client protocol to the server over a Unix socket. The MCP server for coding agents is one more client of that protocol, not a parallel path.
- **The kaikai side** holds one **session** per project root. Planner, scheduler, cache, and scorer are pure logic behind effect rows; only the edges spawn processes or touch the network.
- **Each kalku** speaks the kalku protocol (`docs/protocol.md`) over stdio. Kalku are supervised actors on the kaikai side and are meant to stay warm: a hung cast is aborted inside the kalku, and a kalku is banished only when it stops answering (see *Timeouts and dirty state*).
- **The reni** lives under the machine's temporary directory, at `$TMPDIR/kalku/<name>-<key>/`: the project's folder name, so a person can find it, and a short hash of its absolute path, so two checkouts whose folders share a name never share one. A project's `.kalku.toml` can set another place. It holds build artefacts, caches, and the copies some kalku cast into; the working tree is only ever read.

### One binary, two lifetimes

The CLI and the server are the same code. `kalku run` runs the orchestrator in-process for exactly one run and exits: no server, no socket. `kalku serve` keeps it running for clients and agents, with the kalku a run leaves warm. A CLI run is a server whose lifetime is one run, so nothing is written twice.

## Kinds of kalku

Not every language needs kalku to do the mutating. Where a mature mutation framework exists, a kalku can drive it instead. A kalku announces which kind it is through `ready.capabilities`, and the kaikai side offers what that kind allows.

| Kind | For | Who runs the loop | Warm between runs |
|---|---|---|---|
| **native** | languages with no framework (Elixir) | the kaikai side: `sites`, `cast`, `reload` | yes: the kalku's runtime stays loaded |
| **driver, API** | frameworks with a programmatic API (e.g. Stryker's JS API) | the framework, kept alive by the kalku | yes: the kalku keeps the framework alive |
| **driver, batch** | frameworks that are only a CLI | the framework, one process per run | no: the framework starts and exits each run |

A framework that would help a native kalku — for instance, to reuse its spells — is used as a **library inside** the kalku; the kalku still owns its runtime.

### What a driver keeps and loses

A driver kalku still gets everything that needs only a file and a line: survivors-on-changed-lines gating, the ratchet, `--format github`, `kalku merge`, and one gate and one report across every language in a monorepo — something no single framework offers.

It loses what depends on owning the loop: kalku's coverage-based selection, trivial compiler equivalence, and — when the framework does not expose the enclosing function — the semantic key for declared equivalents, which falls back to a line-based key. The report says so.

### Normalisation

Frameworks count differently: some treat timeouts, or memory errors, as kills. A driver never forwards a framework's score. It maps each individual status to a kalku outcome, keeps the original in `tool_status`, and the kaikai side recomputes the score with its own formula:

| Framework status | kalku outcome |
|---|---|
| Killed | `killed` |
| Survived | `survived` |
| Timeout | `timeout` (outside the score) |
| NoCoverage | `no_coverage` |
| CompileError | `compile_error` |
| RuntimeError, MemoryError | `crashed` (outside the score) |
| Ignored | excluded |

kalku's score for a project will therefore often be lower than the framework's own. That is the point, and the docs say it up front.

Where frameworks emit the shared *mutation-testing-report-schema* (from the mutation-testing-elements project), one driver can read all of them. kalku also emits that schema as an output format, so its reports work with existing viewers.

### The Rust kalku

Rust has a mutation framework, `cargo-mutants`, and the rule above says to wrap a framework where one exists. kalku does not wrap it, and the reasons are specific rather than a preference:

- **It would be a driver, and the driver path does not exist yet.** The protocol defines `delegate`; the orchestrator does not answer it. A native kalku needs nothing the orchestrator lacks.
- **It would put the whole product behind one maintainer's output format.** Nearly all of its commits come from a single person, and its own documentation says the contents of `mutants.out` are *subject to change in future versions*. A native kalku depends on the language's parser, which is as stable as the language.
- **kalku's value is what it adds around the mutation:** the shared spells and their hints, `exclude_calls`, declared equivalents keyed to the enclosing function, the gate on changed lines, one report across languages. A driver gets those only as far as the framework's output allows.

So the Rust kalku is native, in the shape of the Elixir one: it finds sites with the language's own parser — `syn`, in a Rust program, never a scan of the text — and it owns the cast. It measures the **2024 edition only**, which needs Rust 1.85 or later: one grammar to be right about. A package on an older edition is skipped, with that reason, rather than read with rules that may not apply to it.

What it does not copy from the BEAM is the warm runtime. A Rust test binary cannot be hot-loaded, so a cast rebuilds, the way the kaikai kalku's does; "warm" means a warm `target` directory in the reni. It announces `cast` and nothing it cannot do.

The loop, in the reni: `prepare` copies the project there (without `target` or `.git`) — or, when `copy_from` under `[rust]` names a directory that holds the project, relative to it, that directory, and builds the crate where it sits inside the copy. A crate in a larger repository may read files beside it, as a Tauri app's `src-tauri` reads `include_str!("../../docs/x")`, and a copy of the crate alone cannot build: the compiler then says it could not read the file, and the kalku adds that the file is outside what it copies and names the setting. Everything beside the project in such a copy goes into every package's fingerprint, since which of it a test reads is not known. A `copy_from` that does not hold the project, or any other key under `[rust]`, is `bad_options`. The kalku builds every test executable with `cargo test --no-run`; a build that fails is `prepare_failed`, with the compiler's own words. `baseline` lists each executable's tests and runs each one alone, because stable libtest has neither per-test timings nor JSON. A test's id is its file and name, `src/lib.rs::tests::one_is_inside`, so it is stable and unique across packages. `cast` splices the wekufe into the copy, rebuilds, runs the listed tests grouped by executable, and restores the file whatever happened: a build error is `compile_error`, a failing test is `killed`, and a requested test that did not pass or did not report is never a survivor. Cargo gets only the environment the run needs. It answers `abort`: the line is read on a thread of its own while the cast runs, cargo and everything it started (a test binary is cargo's grandchild) are killed with their process group, the file is put back, and the same kalku goes on to the next cast. The kaikai side recycles it only if it says it could not restore.

Where the toolchain has the LLVM tools (the rustup component `llvm-tools`, found in the toolchain's own directory), the kalku also announces `per_test_coverage`. The baseline then builds the project a second time with `-C instrument-coverage`, into a target directory of its own, runs each test alone with its own profile files, and asks LLVM which lines each one executed; the map goes inline or, past `inline_limit_bytes`, into a JSON file in the reni. A cast then runs only the tests that reach the wekufe's line, and a line no test reaches is `no_coverage`, not a survivor. Without the tools the capability is simply not claimed and every cast runs the whole suite. Measured on the kalku's own crate (16 sites in one file, 6 workers): the baseline costs more (114 s against 42 s, the second build and a profile read per test), each cast costs less (9 to 11 tests instead of 170; the cast phase went from 131 s to 68 s), so it pays from a few dozen sites on.

A baseline runs only the packages something changed under (`partial_baseline`). A test executable is compiled from its package and everything the package depends on, with generics and macros expanded where they are used, so a test's fingerprint is its package's, taken over what the build reads: every file under the package's directory, which is also what its tests read, and the same of every package of the copy it depends on, by any kind of dependency, all the way down; `Cargo.lock` for what comes from a registry or from git; the root's manifest, `.cargo/config.toml` and `rust-toolchain` files; what `rustc -vV` says; the variables the tests are given, but for the ones that only say where things are; and the kalku's version. The built executable is not hashed, because paths are compiled into it. Each test runs alone anyway, so a test offered with the fingerprint it has now is left out by itself and listed as reused, and one that failed is given no fingerprint. A change to a package reruns the tests of every package built from it, and no others. What a test reads from outside its package's directory is not in its fingerprint, unless `copy_from` copied it. On a copy of this kalku's own crate (204 tests, one file of sites), a run took 3 min 32 s with the whole baseline and 38 s with every test that had passed reused (the baseline went from 181 s to 9 s), with the same verdicts.

Its spells are the shared ones, with Rust's own judgement about where each is worth proposing:

| Spell | Rust forms |
|---|---|
| `arm` | delete a `match` arm — only when a catch-all remains, because without one the wekufe is not exhaustive and the compiler, not a test, is what rejects it |
| `compare` | `>=`↔`>`, `<`↔`<=`, `==`↔`!=`, in expressions and in match guards |
| `connect` | `&&`↔`\|\|` — not in a `let` chain, where `\|\|` cannot bind |
| `negate` | `if c` → `if !(c)`, and drop a `!` |
| `literal` | `n`→`n+1` for plain decimals within their type's range, `true`↔`false`, a non-empty string → `""` |
| `return` | the value a function returns — each `return <expr>` and the expression its body ends with — → the neutral value of the type its signature writes: an integer `0`, a float `0.0` (`1.0` for a `0.0`), `bool` `false`, `String` and `Vec` their `::new()` under the path the signature uses, `&str` `""`, `Option` `None`, `Result<T, _>` `Ok` of the neutral `T` |

`return` names a value only where the signature does: `()`, `Result<(), _>`, generics, `Self`, `impl Trait`, references other than `&str`, and the project's own types get no site, because a value guessed for them would be a compile error that costs a build. Nor does a value that already is the neutral one, a `true`, `false`, `0` or string literal, which `literal` already changes the same way, a `const fn`, whose value can be computed where no test sees it, a closure's or an async block's own `return`, or a body that ends in a loop or a `panic!`-like macro, whose value is returned elsewhere or never. Measured on `rand_core` (13 wekufe: 9 killed, 4 survived) and on seven files of this kalku's own crate (46: 45 killed, 1 survived), none of them a compile error and none of the survivors equivalent: each was a forwarding method or a helper whose value no test looked at.

`call` is not offered: dropping a call in Rust almost never keeps the types, so it would propose compile errors and little else, and each of those costs a build. Nothing is proposed in tests, in `unsafe fn`, in patterns, types or attributes (a literal there is structure, not a value), or in the arguments of a macro, which are tokens the parser does not read.

### The Python kalku

Python has mutation frameworks (`mutmut`, `cosmic-ray`), and the rule above says to wrap a framework where one exists. As with Rust, kalku does not: the driver path is not implemented, and what makes kalku worth running — the shared spells and their hints, `exclude_calls`, equivalents keyed to the enclosing function, per-test selection by coverage, the gate on changed lines — needs the kalku to own the loop. The Python kalku is native, in the shape of the others, and written in Python.

- **It runs inside the project's own interpreter.** The tests need the project's packages and its pytest, so the summoning picks the active environment, then `.venv` or `venv`, then `python3` (`KALKU_PYTHON` overrides). It therefore uses the standard library and nothing else, and ships as a single zipapp (`kalku-python`), so it never chooses which version of anything the project gets. It needs Python 3.12 or later, for `sys.monitoring`.
- **Sites come from `ast` and `tokenize`**, never from a scan of the text: `ast` gives every node's span, and the tokens give where an operator sits between two operands, which `ast` does not keep. Columns are bytes in `ast` and characters in the protocol, so one module turns between them.
- **A cast forks.** The parent is the warm part: it has pytest imported and, after a baseline, what the tests import from outside the project. Each run of the suite happens in a forked child that is thrown away, so there is no state to reset, an `abort` is a kill of the child's process group, and a wekufe cannot leak into the next. The wekufe is applied in memory: the importer serves the changed module from a string, so no file is written and no bytecode cache can serve the original. Nothing uses threads, because forking a process that has them is how a child inherits a lock nobody will release.
- **Coverage is `sys.monitoring`**, cleared before each test and read after it. What ran at import — a function's header, its decorators and defaults, a module's constants — ran before any test started, so it is credited to the tests that run what it belongs to, from the syntax tree.
- **A baseline runs only the test files something changed under** (`partial_baseline`). The unit is the test file, since what its tests share runs once for all of them. Its fingerprint covers the file, the `conftest.py` files on its path, the `__init__.py` of each package they are in, and every module those import, transitively, as the syntax tree reads it (a name in `pytest_plugins` included), with the files that are not Python beside that code and in the data directories below it; and, for every file, the configuration and lock files at the top level, every file under `tests/` or `test/` that is not Python, and the versions of the interpreter, pytest, the kalku and every installed distribution. Python imports at run time, so the run says which of the project's modules it imported; those no file's closure explains go into every fingerprint given in that run, named in the fingerprint so that the next run can take them again. A file is fingerprinted only when every source its tests executed is one the fingerprint covers and none of its tests failed. A file is left out only when every test the collection found in it is offered with the fingerprint it has now; it is still collected, so what the run imports is what a whole run would, and only its tests are deselected. On `boltons`, 525 tests in 33 files, a second run reused every test and went from 16 s to 11 s with the same verdicts; a change to `strutils.py` ran again the 35 tests of the two files that import it.
- **A reload copies and collects again.** Nothing of the project is held in the parent, since every run imports it afresh in a child and no bytecode is written, so `reload` brings the named files of the copy up to date (removing one the project no longer has) and collects the suite again: an edit may add a test, drop one, or leave a suite that no longer loads, which is a fatal `load_failed` that sends the kaikai side to a fresh kalku and its `prepare`. A path outside the project is refused the same way.
- **pytest is configured by the project.** Its `addopts` select what is run (`click` deselects thirty thousand stress tests with one) and are kept, minus what cannot run here: other processes (`-n`, `--dist`) and another coverage tool (`--cov*`).

| Spell | Python forms |
|---|---|
| `arm` | delete a `case` of a `match`, whole |
| `compare` | `>=`↔`>`, `<=`↔`<`, `==`↔`!=`, `is`↔`is not`, `in`↔`not in` |
| `connect` | `and`↔`or`, one operator at a time in a chain |
| `negate` | `not x` becomes `x`, and a condition `c` becomes `not (c)` (`if`, `elif`, `while`, a conditional expression) |
| `literal` | `n`→`n+1` for plain decimals, `True`↔`False`, a non-empty string → `""` |
| `call` | a call that is a whole statement, awaited or not, becomes `pass` |
| `return` | `return x` becomes `return None`, unless `x` already is `None` or `...`, or is `True` or `False`, which `literal` already flips and which, for `False`, only an identity check tells from `None` |

Not proposed: a `return` whose function gives back `None` whatever it returns (`__init__`, a function annotated `-> None`, a generator), docstrings and bare strings, module dunders (`__all__`, `__version__`), annotations, the type a `cast(...)` or `TypeVar(...)` names, `if TYPE_CHECKING:`, `if __name__ == "__main__":`, the patterns of a `match`, the arguments of a call `exclude_calls` names, and test files. Only `arm` can leave a file that does not parse, so only its candidates are parsed before anyone is asked to cast them.

### The Java kalku

Java has a mutation framework, PIT, and it is a good one. kalku does not wrap it, for the reasons it does not wrap `cargo-mutants`: the driver path is not implemented, and nearly all of PIT's commits come from a single person, who also sells its commercial extension. The Java kalku is native, in the shape of the others, and written in Java.

What a Java tool has to survive is the JDK itself: a release every six months, each with a new class-file version and often new syntax. A tool that mutates bytecode needs its bytecode library taught each of them. This kalku is built on one rule instead: **it reads and writes nothing whose format changes with the JDK, except through the project's own JDK.**

- **It runs on the project's own JDK**, Java 11 or later, as the Python kalku runs in the project's interpreter. Its own code is compiled for Java 11 and has no dependencies, so it never chooses which version of anything the project gets. Java 8 is not supported: a jar built for 11 does not load there, so the summoning script reads the version first and refuses by name, rather than let the kalku die with a class-version error and a closed pipe.
- **Sites come from `javac`**, the compiler that will build the project, through its public tree API (`com.sun.source`, in the module `jdk.compiler`) and nothing private to one release. The grammar is therefore always the project's own. A file this JDK cannot parse is skipped with the compiler's own words; the parser recovers from an error and still returns a tree, and a tree it had to guess at is never searched.
- **A construct newer than Java 11 is reached without naming it.** The kalku is compiled for 11, so it cannot refer to the type of a switch expression or of a pattern. The running JDK's own tree scanner walks them, and the kalku tells them apart by the name of their kind. The same jar finds the cases of a `switch` expression on Java 17 and the guard of a pattern on Java 21.
- **A runtime with no compiler** — a JRE, or an image cut down with `jlink` — is answered in the protocol, `toolchain_missing`, with the reason.
- **The channel is bytes.** Lines are read and written as UTF-8 on the real descriptors, whatever the platform's charset is, because the JDK's default has not been the same on every release; `System.out` is pointed at stderr. No real is ever encoded: the shortest text of a double is not the same on every JDK.

It finds sites and casts them, in a Maven or a Gradle project of one module or several, whose tests are written for JUnit 5 or later, for JUnit 4, or for TestNG. `kalku init` takes a `pom.xml` for a Java project and writes a summoning that starts `kalku-java`, which ships as one file: the script that picks the JDK and turns away one that is too old, with the jar behind it.

The loop, in the reni:

- **`prepare` copies the project and lets Maven build the copy.** Maven writes `target/` beside the pom, so it never runs in the user's tree. kalku's own files (`.kalku.toml`, `.kalku/`) are not copied, and the checks a build makes beyond compiling — licence headers, style, the repository's history — are switched off by the names their plugins give: the copy is not the project as its owner keeps it, and a build that checks every file for a licence header failed on kalku's own. The copy is synced by content, and a changed file is stamped with the time of the copy, because Maven goes by times; when nothing changed, and the same JDK built what is there, Maven is not started again. One Maven run compiles the sources and the tests and writes down two things: the class path the tests run with, and the project as Maven resolved it, from which the kalku reads what the build tells `javac` (release, encoding, `-parameters`, `--enable-preview`, the annotation processors it names) and what it tells the tests' JVM (surefire's `argLine` and system properties). Everything the build prints goes to a file, and every few seconds what is new in it is passed on to stderr, a few lines at most: a build can take long with nothing wrong, the first one fetching everything a project depends on and its build tool besides, and whoever waits then sees that it is alive and, if they give up, is left with the build's last words and not with silence.
- **The workers' Maven builds take turns.** Each worker builds a copy of its own, and every copy is built against the one local repository the user's Maven keeps, which Maven does not lock between processes unless told to. Before 3.9, two builds fetching one artifact at once write it through one half-written file, and one renames it away under the other: eight workers preparing at once against an empty repository lost a worker in four trials of six with Maven 3.6.3 and with 3.8.8, the build failing on a transfer or on a plugin it could not read, though no file was left damaged. So every Maven run of a kalku waits for those of the other workers of the reni, by a lock the operating system lets go of when a worker dies. With it, eight workers prepared in each of three trials with 3.6.3; it costs about 2 s a worker once the repository holds what the project needs, and the workers summoned after the first find fetched what they need. Gradle is not held to it: its cache locks itself between builds, and six builds at once against an empty one all ended well.
- **The JUnit launcher is fetched to fit the project.** The project's test class path has JUnit's engines; what starts them is not there, because Maven's own test runner brings it. The kalku has Maven fetch the launcher at the version of the project's engines, and compiles its test runner — which travels in the jar as source — in the reni, with the project's JDK, against that launcher. There is no version of JUnit the kalku was built for: the same runner is compiled against JUnit 5.4 and against 6.
- **Tests never run in the kalku's own JVM.** The baseline's runs each have a JVM of their own that is thrown away. A wekufe that calls `System.exit`, that takes all the memory, or that leaves something in a static field cannot touch the kalku, so `dirty` is always false. The child's environment is a short list of what a build needs plus what `hello` gave; its output goes to a file; it starts with `-XX:+ExitOnOutOfMemoryError`; and it ends when its standard input closes, which is when the kalku ends, however that happens.
- **A cast's tests run first in a JVM that stays.** Starting a JVM and JUnit took about 0.6 s, more than most casts' tests. So each module keeps one JVM for its casts, with nothing on its class path but the runner and the JUnit Platform, and each run reads the cast's classes, the project's, its tests and everything they use — the engines too — with a class loader of that run's own, on a thread of its own. A static field of the project is a field of that run's classes and goes with them. What a class loader cannot keep apart is what lives outside every one of them: the JDK's own state, which a test can fill with whatever it likes, and threads a test left running. A test that keeps what it built in `System.getProperties()`, as a framework may keep a context, hands a later cast an earlier cast's code, and a JVM that stays, believed, then calls a wekufe its test notices a survivor and one it never reaches a kill (`projects/cached` is written to do both). So that JVM is believed only where it cannot be wrong that way. A kill is believed when the test that noticed passes on the original code in the same JVM; when it does not, it failed for something the JVM held, the JVM is ended, and the cast is asked again of a JVM of its own. A survivor is never believed: it is always asked again of a JVM of its own, which is what says it. Any run that ends without a verdict is asked again the same way. The JVM is replaced when a run leaves threads running, after 200 runs, on `abort`, and on `prepare` and `reload`; a module whose JVM that stays had to be doubted three times casts the rest of the run in JVMs of their own.
- **`abort` ends the tests' JVM, and the kalku stays.** A cast that runs long is not stopped from inside a JVM, where a thread in a loop cannot be: the JVM it runs in is ended, with everything it started, listed before any of it is ended because a process whose parent has died can no longer be found through it. The cast gets no outcome, `restored` says whether all of it is gone, and the same kalku takes the next cast. The channel is read by a thread of its own so that `abort` is heard while a cast runs; the compiler runs inside the kalku and is not stopped half way, but what was asked meanwhile is heard before a JVM is started. An input that ends under a cast ends it the same way: nobody is left to hear its outcome.
- **Coverage per test is counted by JaCoCo and read away from the tests.** After a green baseline the suite runs once more with JaCoCo's agent, which Maven fetches into the reni as it fetches the launcher. What ran is taken from the agent after each test; what reads those counts needs a bytecode library, so it runs afterwards in a JVM of its own and is never on the class path of the project's tests, where a project that uses the same library would get another version of it than its build gives it. The other way to count, line breakpoints through the JDK's own debugger interface, needs nothing fetched and no bytecode library; measured on a real project it took 65 to 70 s where the plain baseline took 1.3 s and JaCoCo about 3 s, and was left.

  The map is asked site by site, about the statement the site is in, and it errs one way only. A site wrongly left without tests is never cast and drops out of the score; a site with too many only costs time. So every test is named for a site whose lines have **no code** (a constant, which the compiler copies into the classes that use it; the second line of a condition), for what ran **when a class was first used**, and for what ran **outside any one test** (what a suite sets up before its tests). Nothing the agent counted is thrown away.

  A class fills its static fields once in the life of a JVM, in whichever test uses it first, and what it ran then is there for every test after. The counting run leaves classes to be initialised as the tests use them, as a cast does, so a class that reads, when it is first used, something a test set up takes the same path in both. The counts say which test that was: a class whose initialiser ran in a test was first used there. What of that test's lines the initialiser ran is told from the bytecode, erring toward too much: the methods it calls and every override of them in the project, the initialisers of the classes it uses, what code outside the project may call back on an object it makes (what a supertype outside the project declares: `equals`, `compare`, `run`; every method, when that supertype is not the JDK's and cannot be read), lambdas, and every method of a class it names by its literal. Those lines belong to every test. An initialiser that reaches code by reflection — a class named in a string, a method looked up, a service loaded — cannot be followed, and everything the test that first used it ran belongs to every test instead. On Commons CLI this credits 0.7 % more tests to places than initialising every class before the tests did, and every test that did.

  Coverage is **withheld**, and every wekufe cast against the whole suite, whenever it cannot be trusted: the agent or its reader could not be fetched or compiled; a test ended otherwise with the agent counting than without it; the agent could not count in the project's classes, as on a JDK newer than it knows; or nothing was counted in any place that has code, which is what an agent that watched nothing looks like.

  The map goes to the kaikai side packed (`coverage_packed_path`) once it is past what a line carries: every test named once, and the lines of a file that the same tests reach together. Line by line it grew as the places times the tests that reach them, most of it the places every test reaches; on Commons CLI 1.9.0, 650 places and 439 tests, it was 4.8 MB and is 139 KB.- **Tests written for JUnit 4 or for TestNG run through an engine.** Everything that runs tests here speaks to the JUnit Platform, and a project that tests with JUnit 4 or TestNG has nothing of it. So the build tool fetches into the reni the engine that runs that framework on the platform — the vintage engine for JUnit 4, TestNG's own for TestNG — with the platform and a launcher of one same version, and they go after everything on the project's class path, never in place of anything on it: the JUnit 4 or the TestNG the tests run on is the project's own. The runner, the names of tests, coverage and casts are then the same code as for JUnit 5; JUnit 4's `Parameterized` and TestNG's data providers are one test a method, as a parameterized test is. A JUnit 4 older than 4.12, which the vintage engine does not run, is refused by its version.
- **A test is a method**, `com.acme.ParserTest#reads(int, String)`. The invocations of a parameterized test and the tests a factory makes are counted as the method that gives them, and a `@Nested` class is in the file of the class around it. Each result is counted from the listener's own events, never from a summary.
- **A cast compiles one file, in memory.** The site is spliced into the text of the reni's copy, and the project's own `javac`, running inside the kalku, compiles that one file against the classes Maven built, into a directory of the cast's own that goes first on the tests' class path. No source file is ever written.
- **A wekufe in a constant is compiled into everything.** `javac` copies a `static final` constant into every class that uses it, the tests included. Compiling the one file would leave the old value in the others: a wekufe a test does notice would come back as a survivor, and a test that names the constant would fail for no reason. A site in what a `final` field is set to is therefore marked `reload: "dependents"`, and its cast compiles every source and every test of the project again.
- **A site is judged by every module that uses its own.** In a project of several modules Maven is run once, at the top, and says for each module its class path, on which the modules it uses are the directories Maven compiled them into in the reni, never a jar someone installed. Each module's tests run in a JVM of that module's own, from that module's directory, as Maven runs them. A cast compiles the one file in its module and puts the result before every class path, so the tests of a module that uses it see the wekufe too; a wekufe in a constant is compiled again in its module and in every module that uses it, tests included, each against what was compiled again before it. A test's name says which module it is a test of, `core::com.acme.PricesTest#rounds()`, because the same class name can be in two; in a project of one module the name is as it was. Coverage is counted module by module, in that module's classes and in those it uses, so a line of one module that only another's test reaches is credited to that test. What a build left in the `target` of any module of the user's tree is not copied: Maven goes by times and would trust it.
- **A reload compiles what was edited, and leaves the rest to the build.** `reload` brings the copy in the reni up to the project again. When every file that changed is a source or a test that was already there, the kalku's own `javac` compiles, into the directories the build compiles them into, every source of the module it is in and of each module that uses it on its classes, then their tests, and lists their tests again: one file alone would leave a constant's old value in the classes that read it. Anything else — a file added or taken away, a build file, a resource, a module that uses another through a jar — is built again by the build, as `prepare` builds it. What the kalku compiled is not what the build would have, so a kalku summoned after it builds the copy again instead of trusting it. An edit that does not compile ends the kalku with `reload_failed`, and the one summoned after it says why in the build's words.
- **A Gradle build is asked by running it.** A Gradle build is a program, and what it does is only known by running it: nothing about it is read from its files. The copy in the reni is built by Gradle itself, the project's own wrapper first, with a script added from outside the project that makes each of its projects compile and then write down what a cast needs — its classes and sources, what its tests run with, what the build tells the compiler and the tests' JVM. The same run has Gradle fetch, from the repositories the project itself names, what the kalku needs and the project does not bring: the launcher that fits its engines, and the coverage agent with its reader. Maven is not needed. Gradle is started **without its daemon**, because a daemon outlives the build that started it and what a kalku starts ends with it. Gradle puts a project that another one uses on its class path as a jar, where Maven gives a directory; a cast's classes go before it either way. A build whose tests run on another JDK than the kalku's own, through a toolchain, is refused by name; so is one that does not call `useJUnitPlatform()`. From there on a Gradle build and a Maven build are the same thing to a cast, and the same code built by either gives the same outcomes: the fixtures are measured both ways.

- **A baseline runs only the test classes something changed under** (`partial_baseline`). The unit is the test class, since its tests share what its `@BeforeAll` made. Its fingerprint is taken over what was compiled, class by class: the bytecode of every class of the project its tests were seen to run — the counting run instruments the test classes as well as the project's — and of what every test may depend on, which is what ran outside any one test or as a class was first used, and the classes JaCoCo finds no code in, an interface or an annotation, which a test uses without anything being counted. A constant the compiler copied into a class changes that class, so hashing bytecode catches what hashing sources would not. Around the classes go what is not one of them: the jars the tests run with (a module of the project is counted class by class instead), the build files wherever they are, the names of every class the module and the ones it uses could find by looking, the files of those modules that are not compiled sources, what the build tells the tests' JVM, the environment, the JDK and the kalku. Which classes a fingerprint was taken over is kept in the reni under the fingerprint, and a later baseline hashes the same classes as they are then: a test could only run other classes because one of these changed. A fingerprint the reni does not hold is not trusted, and its class runs. A class is left out only when every test it ran is offered with the fingerprint it has now. Tests share a JVM, so one may depend on what another left behind: a test that fails where others were left out is run again with the whole suite, and what that says is the baseline. What a test reads from outside its module's directory, and what one test leaves in a static field for another, are not in a fingerprint. On Apache Commons CLI 1.9.0, a second run over `Util.java` reused all 439 tests the first had kept, in 38 classes, and gave the same 23 kills and 5 survivors; after a change to one method of `GnuParser`, the 78 tests of the four classes that ran it ran again and the other 361 were reused. Most of that project's classes fill static fields when first used, so a change to one of them runs every test again.

What is not a verdict is never given one. A test JVM that ended before its tests did — `System.exit`, out of memory, a crash — did not fail an assertion, so it is not a kill; a cast in which none of the selected tests ran and passed is not a survivor. Both are answered with a fatal `error`, which the kaikai side records as `crashed`, with the reason. And `compile_error` means the wekufe does not compile: when a compile fails, the same file is compiled unchanged the same way, once, and if that fails too the kalku has failed to reproduce the build and says so, rather than count every wekufe in the file as caught.

Measured on the fixture project (2 source files, 5 tests, 14 sites, Java 21): `prepare` takes 3.9 s, nearly all of it Maven; the baseline 0.6 s; a cast took 1.16 s on average with a JVM of its own each, and 0.73 s with the JVM that stays, its survivors asked again included. On a real one, Apache Commons CLI (36 source files, 535 tests), one file's 39 wekufe took 53 s cast against the whole suite and 45 s with coverage, the second run of the suite included; the 27 kills are the same 27, and 3 of the 12 survivors turned out to be code no test reaches at all. On its 1.9.0, the 266 wekufe of `Util.java` and `DefaultParser.java` were cast in 227 s with a JVM each and in 76 s with the JVM that stays, 0.85 s and 0.28 s a cast; the 240 kills and the 26 survivors are the same in both.

What surefire is told about its tests is read from the build: its `includes` and `excludes` (or the properties they are taken from; without them the default class names are used, so `*IT` classes are left to integration tests), the system properties and environment variables it sets, its `argLine`, and `rerunFailingTestsCount`. A build that names its tests some other way — `test`, `includesFile`, `excludesFile` — is refused by name, since kalku would have to guess which classes are that build's tests.

The annotation processors a Maven build names apart from its dependencies (`annotationProcessorPaths`) are resolved the way the compiler plugin resolves them, with what each processor itself depends on: they are written down as the dependencies of a pom of their own, by the version the build states or manages, and Maven builds that pom's class path, once, in the module's build directory of the copy so that the project's Maven settings apply. A processor such as AutoService loads only with auto-common and Guava beside it. Should Maven not resolve them, each processor's own jar is the path, and one that needs more fails to load: a cast then says it cannot compile the file even unchanged, and blames no wekufe. Gradle hands over the whole resolved processor path itself.

| Spell | Java forms |
|---|---|
| `arm` | delete a `case` of a `switch`, statement or expression — only while a `default` remains, because without one a switch that has to be exhaustive no longer is, and the compiler, not a test, is what rejects it |
| `compare` | `>=`↔`>`, `<=`↔`<`, `==`↔`!=` |
| `connect` | `&&`↔`\|\|` |
| `negate` | a `!` is dropped, and the condition `c` of an `if` becomes `!(c)` |
| `literal` | `n`→`n+1` for plain decimal `int` and `long` literals within their type's range, `true`↔`false`, a non-empty string or text block → `""`; literals joined by `+` are one constant to the compiler, and one site |
| `call` | a call that is a whole statement becomes the empty statement `;`, so what follows it is still what follows it |
| `return` | the value a `return` gives back becomes the neutral one of the method's declared return type: `0` for a numeric primitive, `char` among them, `false` for `boolean`, the same value boxed for a boxed one, `""` for `String`, `empty()` for `Optional` and its primitive kin, the empty one of `java.util.Collections` for a `List`, `Set`, `Map`, `Collection` or `Iterable` that is `java.util`'s by the file's imports, and `null` for any other reference type |

Not proposed: annotations and the default of an annotation's element; `serialVersionUID`, which no test of the program can tell apart; the labels of a `case`, whose literals are structure (its guard and its body are code); a condition that binds a pattern variable (`o instanceof String s && …`), where negating or reconnecting it changes what is in scope; `this(...)` and `super(...)`; a call in the header of a `for` or as the whole body of a `case … ->`, where no empty statement can stand; hexadecimal, octal, binary and underscored numbers; the arguments of a call that `exclude_calls` names; test sources, known by where Maven and Gradle keep them and by how test classes are named. A `return` wekufe is not proposed in a constructor or a `void` method; for a value that already is the neutral one, or `null`; for `true` or `false`, which `literal` already flips; in `hashCode()`, for which `0` is as valid an answer as any; for the `null` of a method annotated `@NonNull`, `@Nonnull` or `@NotNull`, which tools hold to it; in a lambda, whose return type is not written down; or for a value that calls a method or a constructor inside the body of a `try` with a `catch`, since that call may be the only thing that throws what the `catch` names, and a `catch` of a checked exception nothing throws does not compile. The collections' empty values are `java.util.Collections`'s, written in full, because `List.of()` needs Java 9 and a project may compile for 8.

`enclosing` names a method with its package, its classes and the types of its parameters as the source writes them — `com.acme.Parser.next(int,List<String>)` — because overloads with the same number of parameters are ordinary in Java. A constructor is `<init>`, and an anonymous class is `$1`, `$2`, counted within its top-level class.

### The Go kalku

Go has no mutation tool that is maintained and widely used, which is the case kalku is native for. The Go kalku is written in Go, with the standard library and nothing else, and ships as one static binary, `kalku-go`.

- **It runs the project's own `go`.** Every command is the `go` on the PATH, started in the module, so the toolchain is the one the module's `go.mod` asks for. Go 1.21 is the oldest it measures, the first whose `go` command chooses that toolchain; an older one is refused by name in answer to `hello`. The kalku's own parser is the one it was built with, so a release is built by the newest Go.
- **Sites come from `go/parser`**, never from a scan of the text. A file the build leaves out on this machine — `_windows.go`, a `//go:build` line that does not hold — is skipped as `not_built`, because every wekufe in it would survive for having never been compiled; so are generated files, test files, and a file that belongs to a module nested inside this one.
- **`prepare` copies the module into the reni** — everything but `.git` — and has `go test -list` build every test binary and name the tests in them. The build cache is the reni's own (`GOCACHE`), and `GOWORK=off`: the module's `go.mod` chooses every dependency, as it does for the module's own CI. A test is a top-level `Test`, `Example` or `Fuzz` function, named by its file: `internal/lexer/scan_test.go:TestScan`. A subtest is part of the test that ran it.
- **A wekufe is never written into the copy.** It is written beside it and handed to `go test` through `-overlay`, which builds the copy as though that file held the wekufe. Nothing a cast does needs undoing, so `dirty` is always false and a killed kalku leaves the copy as it was. The copy is named by its path with every link resolved, because an overlay that names the other spelling of the same file is ignored without a word, and every wekufe would then survive.
- **Only the tests that can see the wekufe run anew.** `prepare` also asks `go list -test` what each package's test binary is built from. A cast runs the packages whose binary holds the mutated package with `-count=1`; every other package's binary is the one an earlier cast built, unchanged, and `go test` may answer for it from its own test cache, which it keeps by the binary and by what the tests read. A baseline runs everything for real.
- **Coverage per test comes from Go's cover tool.** After the baseline, each package's test binary is built once with `-cover -coverpkg=./...` and run once per test, alone, with a profile of its own. A profile counts blocks, and a block is not a line: a `case` expression sits before its clause's block and is run by tests that never enter it. So a test is credited with every line of each function it entered, a superset that never misses one, and a package's variables and constants are credited to every test whose binary holds the package. A test that passes in the suite is run alone up to three times; one that fails each time needs another test before it, what it reaches alone is not what it reaches in the suite, and the coverage is withheld with its name and its failure.
- **A baseline runs only the packages that changed** (`partial_baseline`). A test's fingerprint is its package's: every file of each of the module's packages its test binary is built from, what they embed, `go.mod` and `go.sum` for everything from elsewhere, the toolchain's version and the `go env` settings that change a build, and the tested package's whole directory, `testdata` and all. A test offered with the fingerprint it has now is left out and listed as reused. A change to a package reruns the tests of every package whose binary holds it, and no others; the module's root holds everything below it, so its tests always run. What a test reads from outside its own directory is not in its fingerprint. On the fixture module, a run of two files took 11 s with the whole suite and 7 s with every test reused, with the same verdicts.
- **The verdict is counted from the tests.** Every top-level test's `pass`, `fail` or `skip` is read from `go test -json`, never a package's summary. A test binary that did not build is `compile_error`, with what the compiler said under the file's own name; a test that failed, or started and never finished because the wekufe took its binary down, is the kill; a selected test that never started is `crashed`.
- **`abort` ends the cast's process group.** `go test` is started in a group of its own, because a test binary is its child; the abort kills the group and the next request is served. Input that ends with no `shutdown` ends every group under way, and then the kalku.

Measured on the fixture module (3 packages, 6 tests, 17 sites): `kalku run --all` takes 18.6 s with a cold cache, and a cast 0.6 to 1 s. On a real one, `google/uuid` v1.6.0 (43 tests), `version4.go` and `util.go` gave 286 wekufe in 10 minutes: 7 of them were timeouts at the 60 s floor, a lock that a wekufe never released, and the other 279 casts took 0.65 s on average. 231 of the 236 survivors are entries of one lookup table, `[256]byte`, that no test reads. Cast against the tests coverage chose, the same files gave the same 41 kills, 236 survivors, 7 timeouts and 2 crashes; one of the project's tests, `TestVersion6`, fails about one run in ten and passed on being asked again.

Not claimed: `hot_load` (each cast links a test binary) and `code_hash`.

| Spell | Go forms |
|---|---|
| `arm` | delete a clause of a `switch` or a type switch — never `default`, and never the last clause, which is what a switch is most often written to fall to |
| `compare` | `>=`↔`>`, `<=`↔`<`, `==`↔`!=` |
| `connect` | `&&`↔`\|\|` |
| `negate` | the condition `c` of an `if` or a `for` becomes `!(c)` |
| `literal` | `n`→`n+1` for plain decimals — `n-1` for the largest value of a sized type, `255` or `32767`, which is most often that type — and `true`↔`false` |
| `call` | a call that is a whole statement is deleted |
| `return` | each value a `return` gives back becomes its type's zero value, read from the declared result types: `0` for a number, `""`, `false`, `nil` for a pointer, slice, map, channel, function or interface, and `T{}` for a struct declared in the same file |

Not proposed: an integer in an array's length, in a `case` list, or as the key of a composite literal, where changing it changes a type or repeats a case; hexadecimal, octal, binary and underscored numbers; a `true` or `false` that is somebody's variable; a call whose removal would leave an import or a variable unused, or a `panic` that ends its block, each a compile error rather than a wekufe; anything in a call `exclude_calls` names; and a wekufe that no longer parses. A `return` wekufe is not proposed for an `error` result, whose `nil` is another defect than a wrong value; for a type from another file or package, an array or a type parameter, whose zero value the file does not say how to write; for a value that already is the zero value, or a `true` or `false` the `literal` spell already flips; for a bare `return` of named results; or where the value holds the last use of a variable or an import.

`return` measured on the fixture module and on the Go kalku's own sources (171 wekufe): 82 killed, 26 survived, 63 `no_coverage`, and no `compile_error` or `crashed`. One survivor is equivalent, a constant from another package whose value is the zero value; the `no_coverage` are mostly the kalku's service and module handling, which its tests reach through a kalku binary they build.

### The TypeScript kalku

TypeScript has a maintained mutation framework, Stryker, which is the case the design gives a driver for. kalku does not drive it, for the reasons the Rust and Java kalku gave: the driver path is not implemented, and what makes kalku worth running — the shared spells and their hints, `exclude_calls`, equivalents keyed to the enclosing function, per-test selection, the gate on changed lines — needs the kalku to own the loop. The TypeScript kalku is native, written in TypeScript, and ships as one file, `kalku-typescript`, that runs on the project's own Node.

- **Sites come from the TypeScript compiler's parser**, which the kalku carries inside it, and never from a scan of the text. One parser for every project means the same sites whatever version of TypeScript the project installed, and it reads JavaScript as well: `.ts`, `.tsx`, `.mts`, `.cts`, `.js`, `.jsx`, `.mjs` and `.cjs`. The parser counts in UTF-16 code units, so one module turns its positions into the protocol's bytes and characters.
- **It runs the project's own test runner**, Vitest 3 or later, or Jest 29 or later, from the project's `node_modules`, on the Node the kalku was started with, which is the one on the PATH, which a version manager picks for the project. Node 20 is the oldest it runs on; an older one is refused by name in answer to `hello`. The runner is the one the project depends on; a project that depends on both says which with `runner` under `[typescript]`.
- **It runs in the project's tree, and writes nothing there.** A run of the tests is a process of the runner's own, and the wekufe is handed to it in memory. Vitest is run through its Node API with a Vite plugin that answers the mutated file's `load` with the wekufe, so Vite transforms it as it would the file. Jest is run through its own command with every transform the project's configuration resolves to wrapped: the wrapper hands the project's own transformer (ts-jest, babel-jest, …) the wekufe as the file's text, and keys the transformer's cache by that text. Every cache a runner keeps is in the reni; Vitest 5's module cache, which serves transformed files from `node_modules` past every plugin, is turned off; snapshots are never written (`CI=true` for Vitest, `--ci` for Jest); coverage is not collected.
- **A verdict stands only if the wekufe was loaded.** The plugin, or the wrapper, leaves a mark in the reni when it serves the wekufe. A cast whose wekufe was never loaded met no test, whatever the tests did, and is `no_coverage`: so a cache that served the original would be a wekufe nobody measured, not a survivor.
- **A test is its file and its titles**, outermost `describe` first: `test/calc.test.ts:above › is true at the floor`. A cast runs the files of the tests it was given with a name pattern that matches exactly those tests, and reads each test's own status from the runner's JSON report, never a file's or the run's summary. A test asked for that failed is the kill; a test file of the asked tests that did not run is a compile error when the runner said why in the compiler's words (a type error from ts-jest, a syntax error), and a kill otherwise, since it ran at the baseline; a test asked for that never ran is `crashed`.
- **`abort` ends the run's process group.** The runner starts workers of its own; a group of its own takes them all, and the next request is served by the same kalku. Input that ends with no `shutdown` ends every group under way, and then the kalku.
- **Coverage per test comes from counters of its own.** After the run that judges, the baseline runs the suite once more with every source file instrumented by the parser — a counter before each statement, first in each function's body, and around each class field's initializer — served to the runner in memory like a wekufe, with a setup file of the kalku's loaded before the project's. The setup file credits each count to the tests running when it happened; what ran outside any test, a module as it loaded or a `beforeAll`, is credited to every test of its file, since crediting too much costs a cast time and crediting too little makes a survivor. A line belongs to the innermost counted node that holds it, and a function's header to the function, so a test that called it is credited with its parameters' defaults. Nothing is put before a function's declaration, where it would part an overload from its implementation, nor before `super(...)` in a derived constructor, and in TypeScript the counter reaches the global through `any`, so a runner that checks types sees nothing new. Vitest isolates every test file for this run, so each loads the modules it uses itself. A test that passed in the first run and not in the counting one may have stopped before it reached all it reaches: the coverage is then `coverage_doubted`, naming it, and the tests it might be missing from are asked too. When no test that passed passes again in the counting run, it counted nothing, and the coverage is `coverage_withheld` with what the runner said, rather than every line reported as reached by none.

- **A baseline runs only the test files something changed under.** The unit is the test file, since what its tests share runs once for all of them. Its fingerprint covers the file, every module it imports, transitively, as TypeScript resolves them with the project's tsconfig, and the snapshots beside it; and, for every file, `package.json`, the lockfile, the tools' configuration, `.env` files, the runner's setup files and what they import, every file kept with the suite that is not a test, and the versions of Node, the runner and the kalku. A dependency no import names would be missed, so a file is fingerprinted only when every source its tests were seen to execute is one the fingerprint covers, and none of its tests failed or is in doubt. A file is left out only when every test it declares — read with the parser, and so never for a file whose titles are not literals, a table of cases, or a `.only` — is offered with the fingerprint it has now: a test that failed last time is not offered, and its file runs.

Not proposed: anything in a type — an interface, a type alias, an annotation, an ambient declaration, an enum; imports, exports and decorators; a directive such as `"use strict"`; the name of a property; the module a `require`, an `import()` or a `vi.mock` names; the labels of a `case`; the name of a type in a `typeof` guard, where an empty one is a compile error wherever types are checked; the strings of JSX attributes, which are markup; an in-source test block, `if (import.meta.vitest)`; `super(...)`; anything in a call `exclude_calls` names; test files, declaration files, and a tool's configuration (`vitest.config.ts`, `jest.config.js`), which the tools load before a wekufe can be served in its place.

Measured on the Vitest fixture (4 test files, 6 tests, 20 sites): `kalku run --all` took 2 min 10 s, of which two wekufe that loop forever waited out the 60 s floor; every other cast took 0.7 s, against the one test that reaches its line. On the Jest fixture with ts-jest, 6 wekufe in two files took 7.4 s.

Not claimed yet: `hot_load`, since each cast starts the runner; `code_hash`.

| Spell | TypeScript forms |
|---|---|
| `arm` | delete a clause of a `switch` — never `default`, and never the last clause |
| `compare` | `<`↔`<=`, `>`↔`>=`, `===`↔`!==`, `==`↔`!=` |
| `connect` | `&&`↔`\|\|` |
| `negate` | the condition `c` of an `if`, a loop or a conditional expression becomes `!(c)`, and a `!` is dropped |
| `literal` | `n`→`n+1` for plain decimals, `true`↔`false`, a non-empty string → `""` |
| `call` | a call that is a whole statement, awaited or not, becomes an empty block, `{}`, which stands wherever a statement does |
| `return` | the value of a `return`, and the expression an arrow function's body is, becomes the neutral value of the type the function declares: `0` for a `number`, `0n` for a `bigint`, `""` for a `string`, `false` for a `boolean`, `[]` for an array, the same for what an `async` function's `Promise<T>` holds; `undefined` where the type it declares holds `undefined` (`any`, `unknown`, a union with `undefined`), and in a JavaScript file, which declares nothing |

A `return` wekufe is proposed only where the value can be written from the signature. Any other declared type — an object, a class, a type of the project's — gets no site: `undefined` there is a program `tsc` refuses, which a runner that checks types, as ts-jest does, would report as a compile error. Nor does a TypeScript function that declares no type: TypeScript infers it from the value, and `undefined` changes what every caller is given, which the same runner refuses as often as not: on ts-pattern such wekufe were 45 compile errors among 116. Not proposed either: a value that already is the neutral one, or is `null`, `undefined` or `void 0`; a `true`, a `false`, a `0` or a string literal, which `literal` already changes the same way; an arrow whose body is an assignment or a `void`, a statement written as an expression; and a function that declares `void`, `never` or `undefined`, a constructor, a `set` accessor or a generator, whose returned value nobody reads, or reads only through `yield*`.

`return` measured on the Vitest and the Jest fixture (6 wekufe each): 5 killed and 1 survived in both, with no `compile_error`, ts-jest's type check included. The survivor is equivalent: `settle` returns its counter only once a loop has walked it to `0`.

### The C and C++ kalku

C++ has mutation tools, Mull and Dextool, and neither is widely used; Mull mutates LLVM's intermediate code, so its sites do not always map to a span of source a person can read. That is the case kalku is native for. The C++ kalku is written in C++17, with the standard library and nothing else, and ships as one binary, `kalku-cpp`.

- **It reads C++ with the machine's libclang, opened when it runs.** The binary links nothing of LLVM: it opens the libclang beside the `clang` on the PATH, Xcode's, or a distribution's, and calls its C interface, which is stable across releases, so one binary works with whatever Clang is installed and a release is built against none. `KALKU_LIBCLANG` names another. A machine with no libclang is refused by name in answer to `hello`, and so is one with no `cmake`, or a CMake older than 3.21, the first whose CTest writes a JUnit report.
- **Sites come from Clang's parse of each file**, with the flags the build's `compile_commands.json` gives that file, never from a scan of the text. A header is parsed on its own with the flags of a file beside it, of the project's own code, or for a library of headers alone of one of its tests; its sites say `reload: "dependents"`. What a macro writes, or is handed, is not a site: the parse records where the file invokes macros, and no span inside an invocation, or across one, is proposed. Positions are libclang's byte offsets; columns are counted in characters from the file's text.
- **`prepare` copies the project into the reni** — everything but `.git` and any directory CMake built into, a `CMakeCache.txt` being the mark — configures it into a build tree of its own with `CMAKE_EXPORT_COMPILE_COMMANDS` and `BUILD_TESTING` on, and the options `cmake_args` under `[cpp]` — `[c]` for a C project — adds, builds it with the project's own toolchain, and asks CTest for its tests. Ninja is the generator when it is on the PATH and nobody chose one. Under make or Ninja the project is configured and built once, into a base in the reni that no wekufe is ever written into, brought up to date by one worker at a time under a lock and again only when the project or the options changed; each worker's copy and build tree are then copied from the base, every file keeping its date and every text file naming the worker's paths where it named the base's, so the build finds them built and a worker after the first compiles nothing. An executable or a shared library the base linked can name the base's tree where it finds the libraries it loads, and a binary cannot be rewritten as text can, so each one that names the base is removed from the copy and linked again in the worker's tree. Ninja also keeps logs besides the files it built, and those are rewritten for the copy: `.ninja_log` keys each output by a hash of the command that built it, and each command names the base's paths, so every entry is keyed again by the hash of the copy's command, both commands read from Ninja itself (`ninja -t compdb`) and hashed as the log's version hashes them, MurmurHash64A up to version 6 and rapidhash from 7; `.ninja_deps` names the headers each object was compiled from, by path, so each path record is rewritten to name the copy. An entry whose hash is not that of a command Ninja lists for the base, as one whose command reads a response file, is left out, and Ninja builds its output again; a log of a version the kalku does not know, or a header path that would still name the base, leaves the copy with no logs at all, and Ninja builds it whole. A stale header path is the one error that matters, since a header's wekufe would then never reach the objects that include it. A test is a CTest test, named as CTest names it; one declared twice is told apart by where.
- **A wekufe is written into the copy**, never into the tree, the copy is rebuilt, the selected tests run, and the file is put back before the cast is answered — on every path out, an abort included. Make dates what it builds to the second, and an object built in the second the library was last archived in is taken for older than it: under make a cast starts its build in a second after the last one ended, and a file it writes is dated after every build so far. A build that fails is `compile_error`, with the compiler's lines under the file's own name.
- **A test is not linked again for a shared library that changed.** A test loads the shared libraries it links from the build tree when it runs, so the kalku configures with `CMAKE_LINK_DEPENDS_NO_SHARED` on, and a wekufe in a shared library relinks that library alone; one in a static library, or in code a test compiles itself, still relinks every executable it reaches. A build tree that holds a shared library twice, as one does that copies the library beside a test each time the test is linked, is configured with it off, since only that link makes the copy again; and `cmake_args` that name the variable are the project's choice. It matters most on macOS: the first run of each freshly linked executable or library waits for Gatekeeper's scan in `syspolicyd` — XProtect's rules and an online notarisation lookup — at 0.13 to 0.3 s a file, and the scans run one at a time across the whole machine, so a cast that relinks 22 test executables spends 3 to 7 s before its tests do, however many workers share the machine. Linux has no such scan, and a relink there costs what the link does. Apple exempts what a terminal listed under Privacy & Security → Developer Tools starts; that is a setting of the machine for its owner, not something kalku asks for or measured.
- **The verdict is counted from the tests.** Each selected test's own result is read from CTest's JUnit report, never the run's summary. A test that fails is the kill. One that ends by a signal or by its own `TIMEOUT` said nothing about the wekufe: if no other test failed, the cast is `crashed`, naming the test and the signal, and so is a run in which a test asked for did not run.
- **A Catch2 or doctest executable that CTest runs whole is judged case by case.** A project that registers its test executable as one test, `add_test(NAME unit COMMAND unit_tests)`, would have every wekufe judged by the whole executable and every kill named after it. So a test that runs one of the build's executables with no arguments is asked what it is: doctest answers `--version` with its banner, and Catch2 refuses that and answers `--libidentify`; an executable that is neither, and has just run its own tests, is left as it is. The cases are then listed by the framework itself — Catch2 v3's XML listing, v2's `--list-test-names-only`, doctest's `--list-test-cases` — and each is a test named `<ctest test>/<case>`. A cast runs the executable with the cases it was asked for, by their exact names, and reads each case's verdict from the framework's XML report: a case that failed is the kill, and one the framework saw end by a signal is `crashed`. A case can fail when run apart from the cases run before it in the suite, so when cases run alone and one fails, the executable is run whole and its run is the verdict; an executable that fails when none of its cases did failed outside them, and its cases are `crashed`. Arguments are how a project, or a framework's own discovery (`catch_discover_tests`, `doctest_discover_tests`), chooses what a test runs, so a test with arguments stays a CTest test. One that sets a property the kalku would not apply running the executable itself — a regular expression, `WILL_FAIL`, a fixture — runs whole, and so does one whose cases cannot be listed or two of which share a name; the baseline's `differences` say which and why.
- **`abort` ends the process group** of the build or of CTest, puts the file back, and the next request is served by the same kalku. Input that ends with no `shutdown` ends every group under way, and then the kalku.
- **Each test is credited with the lines it ran** (`per_test_coverage`). After the suite has run, the project is configured and built a second time, into a build tree of its own in the reni, with Clang's source-based coverage (`-fprofile-instr-generate -fcoverage-mapping`, added to whatever flags the project was given); each test that passed, a Catch2 or doctest case included, is run alone on it with a profile of its own (`LLVM_PROFILE_FILE`), and `llvm-profdata merge` and `llvm-cov export` against every executable and shared library of that build say which lines it ran. A header's lines are what LLVM's line table makes of every copy of it in every executable, so a test is credited with a header's line when any copy it ran is on that line. A line no test was seen to run — a constant, what the compiler evaluated, a function nothing calls, a header that declares and defines nothing that runs — is credited to every test, which is what judges it with no coverage at all, and so is every line to a test whose run left no profile, such as one that runs a script. The profile is only read by the tools of the release that wrote it: every compiler of the build has to be Clang of one release, and its `llvm-profdata` and `llvm-cov` are looked for beside the compiler, then in the toolchain Xcode selects for Apple's compiler, then on the PATH by the release's name (`llvm-cov-18`) and by the plain name, each taken only when its `--version` names that release. A build GCC compiles, or one whose tools are not found, gets no coverage, and `coverage_withheld` says which; so does a test that passes in the suite and fails each of three runs alone, since what it reaches alone is not what it reaches there.
- **A baseline runs only what changed** (`partial_baseline`). The unit is a CTest test, or a case of a Catch2 or doctest executable. Its fingerprint is taken over what the build says its executable is made of: before configuring, the kalku asks CMake's file API for the codemodel, which names each target's artifacts, the targets it depends on and how it is linked, under any generator; `compile_commands.json` gives each object's command and output, and the compiler's own record of what the compile read — the object's depfile under make, which names it from the top of the build tree, and `ninja -t deps` under Ninja — gives its source and every header. So a test's fingerprint covers the inputs and commands of every object of its executable and of every target it depends on, all the way down, with each target's description; its CTest entry, command, properties and environment, with the reni's paths named alike in every run; the files under its working directory that the build did not write; every file of the project that no compile reads, data and scripts and CMakeLists alike, `.kalku` aside; and the versions of CMake, the compilers, the system and the kalku. A change to a source or a header runs again the tests whose executables are built from it, and no others; a change to any other file runs them all. The file API writes a target's dependencies in an order of its own, and a configure afresh writes `build.ninja` otherwise than one over an existing tree, so neither is taken in as written. A test whose executable is not a target's artifact, whose working directory is outside the copy and the build tree, that needs a CTest fixture, or whose objects have no record of what they read, has no fingerprint and always runs. What a test reads at run time of a file some compile reads, outside its own working directory, is not in its fingerprint. On cJSON v1.7.18 with `cJSON_Utils.c`, one worker under Ninja and `--limit 20`, the baseline took 3.3 s with no store and 0.5 s with every test reused; an edit to `cJSON_Utils.c` ran its 3 tests in 1.5 s and reused the other 19; an edit to `cJSON.h` ran all 22, in 10.3 s, most of it the build instrumented for coverage; a file only touched reused them all. The verdicts of a sample were the same with the store as without it.

Measured on the fixture project (2 test executables, 22 sites, make): `kalku run --all --limit 0` took 83 s, of which one wekufe that loops for good waited out the 60 s floor and was aborted in place; it gave 11 killed, 8 survived and 2 `crashed` by a null pointer the wekufe read through. On a real one, `jarro2783/cxxopts` v3.2.0, its one header gave 446 wekufe in 6 min 18 s with four workers, each cast rebuilding the files that include it: 193 killed, 180 survived, 44 compile errors (the tests build with `-Werror`), 28 crashed and 1 timeout. Two of its tests configure projects of their own and fail under CMake 4 wherever they run; they are left out and named. With the build copied from the base under make, on 150 wekufe of cJSON v1.7.18 and of cxxopts v3.2.0 with four workers, the three workers summoned after the first started casting 0.4 to 1.7 s after it instead of 3.8 to 4.8 s, with the same outcomes; on projects that small the casts, not the build, set how long a run takes. Its Catch2 executable is one CTest test, and is judged as its 34 cases: a sample of 130 wekufe with four workers took 2 min 3 s and gave 56 killed, 54 survived, 18 compile errors and 2 crashed, each crash named after the case a signal ended (`options/Basic options`). With a test no longer linked again for a shared library, on samples of 150 wekufe with the same outcome for each, a sample of cJSON's `cJSON_Utils.c`, whose library its tests load, went from 238 to 216 s with one worker and from 115 to 93 s with four; one of `cJSON.c`, which its tests compile into themselves and so still link, from 733 to 698 s and from 502 to 464 s; cxxopts, a library of headers alone whose every wekufe still links its tests, took 115 s with four workers either way. With coverage per test, on cJSON v1.7.18 (22 CTest tests, `ENABLE_CJSON_UTILS`) a sample of 150 wekufe with four workers took 2 min 33 s where it had taken 18 min 21 s: each wekufe of the sample is reached by one test, and faces that one. The baseline, 0.1 s before, takes 12 to 35 s when it builds the instrumented tree and 2.9 s once that tree is there. Cast against the whole suite instead, the same 150 wekufe gave the same outcomes, one for one: 89 killed, 47 survived, 12 crashed and 2 timeouts.

Not claimed yet: `hot_load`, since each cast rebuilds; `code_hash`.

**C is measured by the same kalku.** Nothing the kalku does is particular to C++: Clang parses both, CMake builds both, and CTest runs both, so a second kalku would be this one with fewer cases. What tells the two apart is each file's compile command, which is what the kalku reads already: a `.c` file is parsed as the C its command compiles, and a header, which says nothing by its name, as the language of the command it borrows. A project that mixes them is measured whole.

- **`kalku init` tells a C project from a C++ one** by what its `CMakeLists.txt` asks CMake for — `project(name C)`, `LANGUAGES C`, a later `enable_language` — and, when it asks for neither and CMake enables both, by whether the project has C++ sources. A C project is `language = "c"`, with the same summoning as C++, and its sources are `.c` and `.h` files; a C++ project's sources take in its `.c` files too. A file named `test.c` is a test as `test_ring.c` is, and a `fuzz` or `fuzzing` directory holds harnesses no test runs, so it is neither code nor suite.
- **A test is a whole executable** as often as not in C — a `main` that returns non-zero, with Unity, CMocka or Check or with nothing — and CTest runs it as one test, so the executable is the unit a cast is judged by and a kill is named after it. A wekufe that reads through a bad pointer ends its test by a signal and is `crashed`; one that loops is a `timeout`; one that does not compile is a `compile_error`. None of them is a kill.
- **A `0` that becomes a pointer is not a site**, in C or C++: it is a null pointer, and a `1` in its place is an integer made a pointer, which the compiler refuses. libclang does not show that conversion, so a file with a `0` among its sites is parsed once more with each such `0` made a `1`, and a `0` that a diagnostic the original did not have marks is dropped. That costs a second parse of the file, and only of a file that has one.
- **The same rules hold**, with what C makes of them: `true` and `false` are macros before C23, so they are not sites; `NULL` is a macro, and a comparison with it is a site while the `NULL` is not; a library's function that a system header defines as a macro, as macOS does with `memset`, is not a call site, which makes the same file give different sites where its headers differ.

A project built without CMake is not measured yet: its `compile_commands.json` would give the sites, but there is no build tree to cast into and no CTest to run.

| Spell | C++ forms |
|---|---|
| `arm` | delete a clause of a `switch`, from its label to the next — never `default`, and never the last clause |
| `compare` | `<`↔`<=`, `>`↔`>=`, `==`↔`!=`, on built-in operands; an overloaded operator is a call |
| `connect` | `&&`↔`\|\|`; the spelled `and` and `or` are left alone |
| `negate` | the condition `c` of an `if`, a loop or a conditional expression becomes `!(c)`, and a `!` is dropped |
| `literal` | `n`→`n+1` for plain decimals, keeping a suffix, `true`↔`false`, a non-empty plain string → `""` |
| `call` | a call that is a whole statement, with its `;`, becomes an empty block, `{}`, which stands wherever a statement does |
| `return` | the value of a `return` becomes the neutral value of the type the function returns, as Clang resolves it: `0` for an integer or a character, `0.0` for a floating type, `false` for a `bool` (`0` in C), `nullptr` for a pointer (`0` in C), and `{}` for a standard `string`, `string_view`, `vector` or `optional` |

`return` names a value only where the type says how to write it: a reference, an enum, a class other than those four, a C `struct`, and a type a template decides get no site, since a value guessed for them would be a compile error that costs a build. Nor does a value that already is the neutral one, a `true` or `false` (`literal` turns those), a value a macro writes, what a `constexpr` or `consteval` function returns, whose value may be taken while compiling, or a lambda's own `return`. A null pointer a test reads through ends the test with a signal, which is `crashed` and not a kill. Measured on the five fixture projects (37 wekufe: 23 killed, 12 survived, 2 `crashed`, no `compile_error` and no `no_coverage`): every survivor is a value no test looks at for the input that tells it apart — a clamp's lower bound tested only where it is `0`, its upper one never, a pointer's target never read — and none is equivalent; the two `crashed` are a C function's string read by `strcmp`. A public project is still to be measured.

Not proposed: test files; what a macro writes or is handed; what is never evaluated — `sizeof`, `alignof`, `static_assert`, a `requires`, a concept, the condition of `if constexpr`, an attribute; an enum's values, a template's arguments and its parameters' defaults; a literal in a `case` label, or one that sizes an array or a bit-field in a declaration; a condition that declares a variable; a call to an operator, a constructor or a destructor, and one to a function that ends a path (`exit`, `abort`, `std::terminate`); hexadecimal, octal, binary and digit-separated numbers; and anything in a call `exclude_calls` names, which matches a function's qualified name, its name alone, or a namespace or class with `::*`.

### The kaikai kalku

kaikai already has `kai mutate`, split the same way the protocol is: `kaic2 --mutate-list-json` catalogues sites from the AST, `kaic2 --mutate-apply <i>` writes the spliced source, and a shell driver owns the run loop. The kaikai kalku is a thin native kalku over those flags:

- `sites` → `--mutate-list-json`;
- `cast` → `--mutate-apply`, write the wekufe into the reni, build, run the covering tests with `kai test`;
- `code_hash` → a hash of the emitted code, which is deterministic in kaikai.

kaikai compiles to native code and cannot hot-load, so "warm" here means a warm compiler and build cache, not a warm runtime; the kalku does not announce `hot_load`.

This kalku needs from kaikai: the full site data in `--mutate-list-json` (span, original and replacement text, enclosing declaration, ordinal), and machine-readable results, test selection and per-test coverage in `kai test` (`--json`, `--only`, `--coverage`), all of which it has. Under a project's own oracle there is no coverage, and every wekufe faces the oracle.

- **A baseline runs only the test modules something changed under** (`partial_baseline`). `kai test --json --list --deps` says, without building anything, which tests each test file holds and which package files its build reads, so the unit is the test module: its fingerprint is taken over the files its build reads, over every file of the package no module builds from — the data a test reads as it runs, `kai.lock` among them, since a build does not say what a test opens — over the module's own test ids, and over the kaikai and kalku versions. A change to a source runs again the modules built from it, and a change to anything else runs them all. A module is left out only when every test it holds is offered with the fingerprint it has now, so one in which a test failed last time runs again whole. Nothing is fingerprinted under an oracle, a command whose inputs are not known to be in the package, nor in a package that names a path dependency or a dependency with no `kai.lock`: what those hold can change while its tree does not. Nothing is kept in the reni, so a fresh checkout reuses what another measured. On a copy of kalku's own kaikai side (514 tests in 58 modules), a run with nothing changed reused every test and went from 46 s to 12 s; after a change to `src/kalku/core/hint.kai` the 134 tests of the 14 modules built from it ran again and the other 380 were reused (34 s), with the same verdicts each time.

`kai mutate` keeps its own driver for kaikai's CI. kalku is written in kaikai, so kaikai's CI must never depend on kalku: that would make the bootstrap circular. The two share the site format, not code.

## Lifecycle of a run

```
$ cd my_app && kalku run --since main
```

### 1. Connect

The CLI looks for `$XDG_RUNTIME_DIR/kalku.sock`. If no server answers, it starts one in the background and waits for the socket. It sends `run {root, scope}` and streams events back.

### 2. Open the session

The server keys sessions by canonical project root. A new root opens a session in state `cold`; a known root reuses its warm session. The language is detected from marker files (`mix.exs` → Elixir). An unknown language is an error, not a guess.

### 3. Prepare the reni

The project is compiled into the reni with the language's own tooling, redirected away from the user's artefacts. For Elixir: `MIX_BUILD_PATH` points into the reni, `MIX_ENV=test`, protocol consolidation disabled so a mutated `defimpl` can take effect. The user's `_build` and working tree are never written.

### 4. Summon the kalku

N kalku are summoned (default: CPU cores). Each loads the compiled project and starts the test environment once. Kalku that need exclusive external resources get their own: for Elixir with a database, each kalku runs with a distinct `MIX_TEST_PARTITION` and therefore its own test database.

### 5. Baseline

One kalku runs the full suite once with per-test coverage. The baseline yields:

- **Green or red.** A test that already fails judges nothing: it would fail against every wekufe it was sent with. A run goes on without such tests, sends them with no cast, and names them in every report beside the score, which is then a score of the tests that pass; the run does not pass a gate (exit `2`). A suite in which nothing passes aborts the run.
- **Coverage map:** `(file, line) → [test ids]`.
- **Durations** per test, from which each wekufe's timeout is derived (`max(3 × covering tests' baseline, floor)`).

**A baseline runs only what changed.** Each run keeps what its baseline measured: every test that passed, with what it reached, what it cost, and the fingerprint its kalku gave it. The next run offers those tests back (`reuse`), and the kalku runs every test except the ones whose fingerprint it finds unchanged. A reused test brings back its coverage and duration from the store, and the run plans with it as if it had run.

The fingerprint is the kalku's, because only the kalku knows what a test runs. Hashing on this side the source files a test's coverage names would miss what that coverage never shows: a macro expanded into the module that calls it, a constant inlined where it is used, a generic instantiated at its caller. So a kalku fingerprints what was compiled and the inputs it knows a test reads; for Go that is the package's test binary, built with coverage, and the package's other files. For Elixir it is a test module's: its file and every file the compiler records it depending on, at compile time or at run time, all the way down, with what every test can reach unnamed — the configuration, `priv/`, `src/`, the non-test files under `test/`, `mix.lock` and the Elixir and OTP versions. A module is the unit because its tests share what `setup_all` made. For Java it is a test class's: the bytecode of every class its tests ran, the classes every test may depend on, and what the module's tests read that is not a class. A dependency the compiler cannot see, such as a module the configuration names, is caught where it matters: a module is fingerprinted only when every file its tests executed is one its fingerprint covers. A test it cannot fingerprint always runs.

The store is not in the project, which may want nothing of kalku in it, and not in the reni, which belongs to one checkout. It belongs to the repository: `~/.cache/kalku/repos/<first commit>/<where the project is in it>/<language>.json` (under `$XDG_CACHE_HOME` when that is set). Every worktree and clone of a repository finds what another measured, and a directory that is not a repository is kept by its path. Sharing is safe because nothing is reused for where it came from: an entry from another branch whose fingerprint differs does not match, and its test runs. A store is about one setting — the kalku's language, version and runtime, and the environment and options the run hands it — and is offered under no other.

A run over the whole project (`--all`, or `scope: all` from a client) runs the whole suite and keeps it; every other run reuses, whether it was asked from the command line, of `kalku serve`, or by an agent through its host. The report says how many tests were reused (`baseline_reused`), because a score built on a reused baseline is a claim about tests that did not run this time. Coverage the kalku withheld or doubted is not kept.

The kalku that ran the baseline is the pool's first worker where it announces `cast_after_baseline`: it has built the project and holds its suite, and summoning another to cast in its place pays for the build again before the first wekufe. A kalku announces it only when a wekufe cast after its baseline is judged as it would be in a kalku that was only prepared; one that does not is banished once it has reported the sites.

### 6. Plan

A kalku reports the sites in the files within scope (`--since <ref>` narrows to changed lines). The planner then:

- drops sites listed in `.kalku/equivalent` (see *Equivalent wekufe*), and fails the run if an entry matches no site;
- marks sites on lines no test covers as `no_coverage` and does not run them;
- reuses cached outcomes whose key is unchanged;
- orders the rest so that cheap, high-signal wekufe run first (fewest and fastest covering tests).

### 7. Cast

For each planned wekufe, the scheduler picks an idle kalku and sends it the site plus the covering tests. The kalku:

1. splices the replacement into the module's source in memory;
2. compiles it; if the compiled code is identical to the original's, stops here and reports `equivalent` (see *Trivial compiler equivalence*);
3. loads it into its runtime — or, if the module is a compile-time dependency of others (macros, computed module attributes, `require`/`import`), recompiles the dependents too;
4. runs only the covering tests, stopping at the first failure;
5. restores the original modules;
6. reports the outcome and the hash of the compiled wekufe.

#### Timeouts and dirty state

A warm kalku is the most expensive thing kalku owns, so killing one is the last resort, never the normal path.

A timeout escalates in three steps:

1. The kaikai side decides the timeout from outside, as always, and sends `abort`.
2. The kalku stops the cast inside its runtime — for Elixir, `Process.exit(pid, :kill)` on the test processes, since a looping wekufe hangs a process, not the VM — restores the original modules, and answers `aborted`. The outcome is `timeout`; the kalku stays warm.
3. Only if `aborted` does not arrive within a grace period (a NIF that never returns, a blocked scheduler) is the OS process killed and a fresh kalku summoned.

A cast may leave global state behind (ETS tables, registered processes, application env). The kalku reports `dirty: true`, and the kaikai side escalates the same way: first `reset` — for Elixir, restart the project's applications and clear its ETS tables — and only if the reset fails, recycle the kalku.

#### Memory

A timeout bounds time, and a wekufe can take the machine well inside it: a loop turned into unbounded growth allocates faster than a cast's deadline passes, and once the machine is thrashing nobody is left to hear `abort`.

So the kaikai side also watches memory, from outside like the timeout. While a kalku is casting it sums the resident size of the kalku and everything it started (the process tree read from `ps`, not the process group: cargo and a forked child live in groups of their own), five times a second. Past `run.memory_limit_mb` the cast ends as `crashed`, with the size and the ceiling in its message, and the kalku is recycled; the abort-first steps are skipped because they depend on the very thing that is starved. It is never a kill: no assertion failed.

Killing a kalku, for this or for any other reason, ends its whole tree. The tree is read before anything is signalled, because once the parent is gone its children belong to `init` and cannot be found again.

The default ceiling is a quarter of the machine's memory shared between the workers and never under 2048 MB. `0` turns it off. The kalku's build and `prepare` are not held to it, since a build can need more than any test.

What it does not cover:

- **Anything outside a cast.** The build, `prepare` and the baseline are not held to the ceiling.
- **Growth faster than the look.** The size is read every 200 ms, so a cast that allocates faster than that goes over the ceiling before it is seen; the ceiling is a bound with a margin, not an exact line. A loop growing at 13 GB/s was ended at about 2.8 GB with a ceiling of 300 MB.
- **A machine where `ps` cannot run.** The size reads as zero and nothing is ended, without a word about it.
- **One kalku against another.** The ceiling is per kalku, so the workers together can hold the number of workers times the ceiling; the default shares the quarter of the machine between them for that reason.
- **Elixir.** Its tests run inside the kalku's own VM, so the ceiling is on that whole process and ending it loses the warm runtime.
- **Windows.** The process table is read with `ps -axo`, `sysctl` and `/proc/meminfo`.


### 8. Report

Outcomes stream to the client as they arrive and are written to the cache. At the end the reporter emits the summary: human text, JSON (`--format json`), agent-oriented JSON (`--format agent`, see *Agents*), and optional GitHub annotations for survivors.

The human report leads with **survivors** — file, line, spell, diff, covering tests — because a survivor is something a reader can act on. The score follows as context and trend, never as the headline.

### 9. Stay warm

After the run the session stays in `ready`. On the next run, the server hashes the source files, tells each kalku to reload only what changed, reuses coverage for untouched tests, and re-casts only the wekufe whose cache key changed.

Of that, what is done today is the pool and the reload. `kalku serve` and `kalku run --watch` keep their pool between runs, and when a file has changed each kalku is sent `reload` with the files that changed, sources and suite alike. The baseline and the sites are asked again on every run, which the incremental baseline keeps to the tests the change can reach: in a watch, of the pool's first kalku where it can cast after a baseline, and otherwise of a kalku summoned to read the project. The pool is replaced instead when it cannot be brought up to date honestly: a file it holds is gone (`reload` names files to load, none to forget), a kalku does not answer `reload` or is no longer there, or the watch's settings summon kalku differently. The watch reads the project's sources, its suite, `.kalku.toml` and `.kalku/equivalent` once a second, and runs again once two reads in a row agree, so the burst of writes one save makes is one run. A signal ends the run that is going, if one is, and then the watch, with the last run's exit code; `--store-baseline`, `--shard` and the `json` and `github` formats are refused with it, since each is about one report.

If a file changes during a run, outcomes for wekufe in that file or covered by tests that read it are discarded, not reported.

### 10. Idle shutdown

A session with no activity for a configurable period (default 15 minutes) banishes its kalku and closes. The reni stays on disk for the next cold start.

## Session states

```
cold ──prepare──► preparing ──ok──► baseline ──green──► ready ◄──────┐
                     │                  │                 │          │
                   error               red              run ──► running
                     ▼                  ▼                 │
                  failed             failed             idle
                                                          ▼
                                                        closed
```

`failed` sessions keep their diagnostics until the next `run`, which restarts from `cold`.

## Outcomes and score

| Outcome | Meaning | In score |
|---|---|---|
| `killed` | a covering test failed | detected |
| `survived` | every covering test passed | undetected |
| `timeout` | exceeded its timeout | reported apart |
| `no_coverage` | no test executes the site: by the baseline's coverage, or seen by the kalku while the wekufe was cast. Code that runs once and not again in a warm runtime is among it where the kalku has no way to run it again | reported apart |
| `compile_error` | the wekufe does not compile | excluded |
| `crashed` | the cast produced no verdict: the kalku died, the run of the tests it started ended before judging (the wekufe took the test runner down), it could not run the tests it was given, or the wekufe held more memory than the ceiling (the message says how much) | reported apart |
| `equivalent` | proven by identical bytecode, or declared with a written reason | excluded |
| `nondeterministic` | cast several times and did not agree with itself | reported apart |

**Score = killed / (killed + survived).** Timeouts, crashes, and missing coverage are shown next to the score, never folded into it: an infinite loop is not a failing assertion, and pretending it is inflates the number. A `compile_error` rate above a threshold is itself a warning: it means a spell is proposing nonsense for that language.

Equivalents are counted next to the score by origin — `score 0.86 · 14 equivalent (9 bytecode, 5 declared)` — so a growing pile of declared suppressions is visible.

## Spells

The shared catalog, in order of diagnostic value:

| Spell | Defect it simulates | Elixir forms |
|---|---|---|
| `arm` | a case stops being handled | delete a clause of `case`, `cond`, `with … else`, `receive`, or a multi-clause function |
| `compare` | off-by-one in a gate | `>=`→`>`, `<`→`<=`, `==`→`!=`, in expressions and `when` guards |
| `connect` | a gate that never or always fires | `and`↔`or`, `&&`↔`\|\|` |
| `negate` | an inverted guard | `if`↔`unless`, `c` → `not c` in conditions |
| `literal` | wrong seed or base case | `0`→`1`, `n`→`n+1`, `true`↔`false`, `:ok`↔`:error`, `""` for a non-empty string |
| `call` | a step that silently does nothing | drop a pipe stage (`\|> f()`), `f(x)` → `x` |
| `return` | a function whose result nobody checks | a `def`/`defp` clause's last expression → `nil`, except a literal `literal` perturbs, a `raise`, or an `@impl` callback, whose result is the behaviour's protocol; other languages: a returned value → their neutral one |

`return` is cast only where `[run] spells` names it. A spell outside the default set moves every project's score the day it is added, so it waits there until it has been measured on real projects in each language and a release says it joins the default. A run that names a spell its kalku does not cast stops and says which, rather than reporting a score over less than was asked for.

A language may add spells; each new spell gets a shared name in the protocol and fixtures in its adapter. Sites are never proposed inside tests, comments, docs, or string contents.

### Concurrency spells

Every spell above is local and syntactic: it changes an expression and asks whether a test notices. None changes *when* something happens, *who* waits for it, or *what happens when it dies* — so a project whose concurrency is untested scores the same as one whose concurrency is covered. On the BEAM that is the most expensive blind spot there is.

| Spell | Defect it simulates | Elixir forms |
|---|---|---|
| `await` | a wait that is no longer a wait | `after N` → `after 0`, `Task.await(t, N)` → `:infinity`, `GenServer.call` → `cast`, a `:timeout` option → `:infinity` |
| `supervise` | a failure that is no longer contained | `:one_for_one` ↔ `:one_for_all` ↔ `:rest_for_one`, `restart: :permanent` → `:temporary`, drop `Process.link/1`, drop `trap_exit: true` |

These break assumptions the rest of kalku rests on, so four rules come with them.

**A hang is not a kill, and it is not silence either.** Remove a deadline and the likely outcome is that the suite stops rather than fails, which is `timeout` — outside the score by design, because an infinite loop is not a failing assertion. For this family the timeout is itself the finding, and it is reported as one: *the suite hangs rather than failing when this wait is removed*. A suite that hangs has not noticed anything; counting it as a kill would be the inflation this project exists to avoid.

**A flaky kill is a dishonest kill.** A concurrency wekufe can die on one run and live on the next, so one cast is not evidence. A wekufe from this family is cast until its outcome agrees with itself — three times by default — and an outcome that does not agree is reported as `nondeterministic`, a category of its own, next to the score rather than in it. A result that changes when nothing changed is a fact about the suite worth reporting and worth nobody's trust as a measurement.

**They are opt-in.** Speed is the product, and these wekufe are slower than any other: some wait out a deadline, some wait out a restart backoff, and each is cast several times. A project asks for them by name — `spells = ["arm", "compare", "await", "supervise"]` — and they want a raised timeout floor. A default run is unchanged.

**Expect declared equivalents.** `after :infinity` in a process that is always messaged first is equivalent, and no bytecode comparison will prove it. The reason line on a declared equivalent matters more here than anywhere else, which is why it is required and why suppressions are reported under their own heading.

`order` — swapping two independent sends — stays out of the catalog until someone prototypes it. It is the most likely of the three to be equivalent by construction and the most likely to be flaky, and a spell whose usual answer is *it depends on the scheduler* measures nothing.

## Equivalent wekufe

Deciding whether two programs are equivalent is undecidable in general, so no tool finds every equivalent wekufe. kalku avoids generating the predictable ones, proves the cheap ones mechanically, and makes declaring the rest honest and durable.

### Three kinds of survivor

| Kind | Example | Treatment |
|---|---|---|
| **Equivalent** | `i >= 0` → `i > 0` where `i` is never 0 at that point | proven by bytecode, or declared with a reason |
| **Unobservable by design** | dropping `Logger.debug/1`, `:telemetry.execute/3`, or a cache that exists only for speed | excluded by configuration (`exclude_calls`), never entry by entry |
| **Hard to kill, but real** | an edge case no test exercises | a hole: write the test, do not suppress |

Keeping unobservable calls out of the equivalent file keeps that file small enough to audit.

### Not generating them

A kalku does not propose sites whose wekufe are equivalent by construction, for example:

- `literal` on text that only reaches a log message, `@moduledoc`/`@doc`, or a `raise` message;
- `negate` on an `if` whose branches are identical;
- sites inside calls matched by `exclude_calls`.

Each such rule has a fixture in the kalku showing the case it avoids. An `arm` on a clause no test executes is not a generation concern: it falls into `no_coverage`.

### Trivial compiler equivalence

If a wekufe compiles to the same code as the original, it is equivalent, and that is a proof, not a guess. The kalku already compiles every wekufe during a cast, so the check costs one comparison: for Elixir, the `.beam` code chunks with debug info and line numbers stripped. An identical result ends the cast with outcome `equivalent`, without running a test.

The kalku also reports a hash of every compiled wekufe. Two wekufe with the same hash are the same program; the cache keys outcomes by that hash too, so a duplicate is cast once.

This is the only way a kalku may report `equivalent`: with mechanical evidence.

### Declared equivalents

The rest are declared in `.kalku/equivalent`, committed with the project. Entries are keyed by meaning, not by line number, so they survive edits elsewhere in the file:

```
lib/my_app/parser.ex  MyApp.Parser.next_token/2  compare  ">="  #1   # index is always even here; > and >= agree
```

Fields: file, enclosing function (`Module.fun/arity`), spell, original text (JSON string), ordinal of that text within the function, and the reason after `#`.

- An entry without a reason is a hard error.
- An entry that matches no site is a hard error: an orphan either suppresses nothing or, worse, would end up suppressing a different wekufe than the one justified.
- The file suppresses; it does not excuse. A wekufe that survives because a test is missing belongs in a test, not here.

## Configuration

`.kalku.toml` at the project root, all keys optional:

```toml
[run]
workers = 8
spells = ["arm", "compare", "connect", "negate", "literal", "call"]
exclude = ["lib/my_app_web/telemetry.ex"]
exclude_calls = ["Logger.*", ":telemetry.execute", "IO.inspect"]
timeout_floor_ms = 500
memory_limit_mb = 8192   # per kalku; 0 for none. Default: a quarter of the machine shared between the workers, at least 2048

[score]
threshold = 0.80        # global floor; a run exits 1 below it
ratchet = true          # the score of the whole project may not drop below the stored baseline

[elixir]
partition_env = "MIX_TEST_PARTITION"
```

A table named after the project's `language` is that kalku's, not kalku's: the kaikai side hands it over as it stands, in `hello.options`, and the kalku refuses a setting it cannot honour. The kaikai kalku takes `oracle`, a command to judge wekufe with in place of `kai test`; the Rust kalku `copy_from`, the directory to copy into the reni when the build reads files beside the crate.

## Caching

Cache entries live in the reni. A wekufe outcome's key is:

`hash(adapter version, spell, site span, source file hash, hashes of the covering tests' files, hashes of the files those tests load)`

Outcomes are also indexed by the compiled wekufe's hash, so identical programs reached from different sites share one cast.

Any change to a key component invalidates it. Keys never use timestamps.

## Running in CI

A shard reports the score of its part, says so first, and is not held to `score.threshold`; `kalku merge` over every shard's report is held to it, and to the ratchet, as a run of the whole project is.

`kalku run` is already the form CI wants: it runs the orchestrator in-process for one run, with no server and no socket, and says what it found in its exit code. There is no separate CI mode, so a run on a laptop and a run in CI are held to the same policy.

### Two cadences

| When | Command | Purpose |
|---|---|---|
| every PR | `kalku run --since origin/<base>` | block the merge if the change introduces holes |
| nightly | `kalku run --all --shard i/n`, then `kalku merge` | track the global score |

A full run on every PR does not scale: a medium Elixir project yields thousands of wekufe.

### What blocks a PR

A PR fails when **it introduces a hole**: a wekufe that survives on a line the PR changed, or a site on one that no test reaches at all. Code no test ran is the larger hole — there was nothing even to fail to notice it. Gating PRs on the global score punishes whoever touches a file with old debt, and teams switch such tools off. The global score is guarded separately by `[score] threshold` and, with `ratchet = true`, by a stored baseline it may not drop below.

Changes to suppressions are surfaced, never silent: if a PR adds entries to `.kalku/equivalent` or widens `exclude`/`exclude_calls` in `.kalku.toml`, the report and the GitHub summary list them under their own heading. Hiding a hole must be as visible as leaving one.

### The ratchet

With `[score] ratchet = true`, a run of the whole project (`kalku run --all`) is held to the baseline in `.kalku/baseline.json`: the two counts a score is made of, `killed` and `survived`, versioned with the project. A score below it blocks with exit 1.

- **A run never writes the baseline unasked.** `kalku run --all --store-baseline` stores the score of that run, and only when the run ended clean. A baseline that moved by itself would hold nothing.
- **Lowering it is loud.** Storing a baseline below the one that is there says so on stderr, and the file's diff shows it in review, like an entry added to `.kalku/equivalent`.
- **No baseline is not a pass.** A project that set the ratchet and stored nothing is not measured against nothing: the run ends with exit 2 and the command that stores one. A checkout that never had the file would otherwise pass for ever.
- **Only the whole project is held to it.** The score of named files, of a change (`--since`) or of a shard is of something else, and is not compared.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | measured; nothing to report |
| `1` | measured; survivors on changed lines, sites on changed lines that no test reaches, or the score is below threshold or baseline |
| `2` | could not measure: tests that fail before anything is cast, failed prepare, orphan equivalent entry, config error, a run that stopped before casting its plan (an interrupt, a pool left with no kalku) |

"Your tests have holes" and "kalku could not run" are different failures and CI must be able to tell them apart.

### Sharding

`--shard i/n` takes a deterministic partition of the planned wekufe (by `site_id`), so shards never overlap and reruns pick the same work. Each shard writes a JSON report; `kalku merge` combines them into one score and one report.

```sh
kalku run --all --shard 1/2 --format json > shard-1.json   # one job
kalku run --all --shard 2/2 --format json > shard-2.json   # another
kalku merge shard-1.json shard-2.json --format github      # after both
```

A merge is a claim about the whole project, so it is refused, with exit 2, unless the reports provably make one: every part `1..n` of the same `n` exactly once; each written by this version of kalku, over the whole project (`--all`), from the same plan (`plan`: the number of sites and a digest of their ids) at the same `commit`; each having measured what it owned, with no `stopped` and no exit 2; and every wekufe a report names owned by its shard. What is missing from a report is a refusal, never a guess. The merged report adds up what each shard cast and says once what every shard saw for the whole project (files no kalku would search, tests left out). `kalku merge --store-baseline` stores the merged score as the ratchet's baseline, by the same rules as a run.

### Cache

The kept baseline is saved between builds with `actions/cache` on `~/.cache/kalku/repos`, keyed by the commit and restored by prefix, so a pull request starts from what `main` measured and its baseline runs only the tests the change can reach. Nothing is written to the repository.

### Output

`--format github` emits an annotation on each survivor's line with the wekufe's diff, and a summary to `$GITHUB_STEP_SUMMARY`. `--format json` is the stable machine-readable report used by `kalku merge`. Besides what a reader wants (score, counts, survivors, what blocks), it carries what a merge checks and rebuilds the report from: `kalku` (the version that wrote it), `planned`, `plan`, `commit` (when the tree is a commit with no tracked file changed), `whole` (a run of `--all`), each survivor's full `site`, `uncovered` and `troubles` (sites and the wekufe outside the score, in full), `left_out_sites`, and `suite`, the tests a survivor judged by the whole suite faced, written once.

### External resources

Kalku run in parallel, so each needs its own external state. For Elixir with Postgres, `prepare` creates one test database per kalku through `MIX_TEST_PARTITION`; the CI job provides the server as a service container.

```yaml
- uses: actions/cache@v4
  with:
    path: ~/.cache/kalku
    key: kalku-${{ hashFiles('mix.lock') }}-${{ github.sha }}
    restore-keys: kalku-${{ hashFiles('mix.lock') }}-
- run: kalku run --since origin/${{ github.base_ref }} --format github
```

## Distribution

kalku ships as a **standalone native binary** per platform (linux-x64, linux-arm64, macOS arm64/x64), plus a GitHub Action that downloads it. Users, and CI images in particular, never need the kaikai toolchain.

Each language's kalku travels inside the binary as source and is compiled into the reni on first use with the project's own toolchain (for Elixir, the project's Elixir/OTP versions), so it always matches the runtime it will be loaded into.

## Self-hosting

kalku runs on its own sources through the kaikai kalku, in CI like any other project: survivors on changed lines block a PR.

The kalku that measures kalku is the **last release**, pinned and bumped deliberately, never the binary built from the same commit. A bug in the kalku under test could otherwise hide its own survivors — the same reason a compiler bootstraps from a previous stage. `make self-mutate` does this, with the release pinned in `.kalku-release`; it is advisory: it reports but does not block.

## Security

Running wekufe is running modified code; serving run requests is remote code execution by definition.

- The server listens on a Unix socket with `0600` permissions, or `127.0.0.1` plus a per-session token. Never on a public interface by default.
- Every cast has a timeout; every kalku is supervised. Killing a kalku's process is the last step of an escalation, not the first response.
- Kalku receive an explicit environment, not the server's.

## Agents

Coding agents write tests quickly, and those tests often *cover* without *verifying*: they execute a line and assert nothing that would change if the line were wrong. Coverage cannot tell the difference; a wekufe can. kalku is the oracle an agent uses to learn whether its tests test anything.

An agent differs from the other two clients in rhythm: a person reads one run, CI gates one run per PR, and an agent **iterates**. Write a test, re-cast the wekufe it targets, repeat. Everything in this section serves that loop.

### The loop

```sh
kalku run --since HEAD --format agent   # survivors, with everything needed to act
kalku show <wekufe>                      # full detail of one
kalku cast <wekufe>...                   # re-cast just these: are they dead now?
```

`kalku cast` re-reads changed files, refreshes the coverage of changed tests, and re-casts only the named wekufe against warm kalku, so the answer takes seconds, not a full run. It works cold too — it then pays for `prepare` and the baseline first.

A kill only counts if the killing test **passes on the original code**. `kalku cast` runs changed tests against the original first; a test that fails there is reported as `test fails on original` and the wekufe stays `survived`. An agent cannot kill a wekufe with a broken test.

### `--format agent`

One JSON object per survivor, complete enough to act on without opening other files, and compact in tokens:

```json
{"wekufe":"c1f3…","file":"lib/my_app/parser.ex","line":42,"enclosing":"MyApp.Parser.next_token/2",
 "spell":"compare","original":">=","replacement":">",
 "context":["defp next_token(s, i) when i >= 0 do","  case String.at(s, i) do"],
 "covering_tests":["test/my_app/parser_test.exs:18"],"covering_count":1,
 "hint":"No test tells i == 0 apart from i > 0. Add a case at the boundary i = 0."}
```

`covering_tests` lists the first five tests that cover the line and `covering_count` how many there are. A line that a whole suite reaches is covered by every test in it, and listing a thousand of them per survivor made a run too large to fit in a tool result; the full list is what `kalku show <wekufe>` (and the `kalku_show` tool) answers.

Where per-test coverage was not measured — a kalku without it, or a run in which it could not be stood behind — both are absent and `judged_by_whole_suite` carries the size of the suite instead. The whole suite ran against the wekufe; which of its tests reach the line is not known, and a few of them listed as covering it would be an attribution nobody made. The human report says the same in words, and the JSON report carries an empty `covering_tests` beside the same field. When the kalku measured it and would not stand behind it, the summary carries why in `coverage_withheld`, in the kalku's own words, and the human report says it once above the score. A kalku that reports coverage it doubts has each wekufe cast against the tests credited with its line and then, when none notices, against every other test: its survivors are judged by the whole suite too, and the summary carries why in `coverage_doubted`. When a kept baseline answered for some of the tests, the summary carries how many in `baseline_reused`, and the human report says it above the score.

Every test that judged a wekufe is listed with what it cost the casting: its duration at the baseline once for every wekufe it judged. A test costs twice in mutation testing, in the suite and again for each wekufe whose line it reaches, so the list is ranked by that product, not by duration: a two-second test that judges three hundred wekufe is ten minutes of casting. The JSON report carries all of them in `test_costs`, the agent summary the five dearest in `dearest_tests`, and the human report the ten dearest above the score when the casting came to a second or more. It is a report, never a decision: no test is skipped or reordered because of it.

A cast stops at the first test that fails, so a run credits one test per kill, and a test that would have killed a wekufe after the one that did is never named. Which tests kill nothing cannot be read from that: it would call the test that ran second useless. `kalku run --all --every-kill` asks for it instead. Each killed wekufe is cast again without the test that killed it, until none of the tests left notices; the casts made again never change what the wekufe came to, only who is known to kill it. A test kills nothing when every wekufe it faced was chased to the end and none fell to it. A wekufe whose chase did not end — a cast that timed out, crashed, or killed without naming the test — is counted as unsure and left out rather than guessed at, and a test that judges no wekufe of the whole plan is named apart, as a line no spell touched rather than a verdict on the test. The JSON report carries every test's weight in `test_weights`, the agent summary the counts and the first names, and the human report the list above the score. The same matrix says which tests are redundant: a test none of whose kills is its alone, because another test holds every one of them (named, and said once for two tests that kill exactly the same wekufe) or the rest of the suite does between them. Each is listed with what it costs the casting, dearest first, in `redundant_tests` and in the human report, which says beside it that any one of them can go without a wekufe living while several at once may not, and that redundancy is against the spells cast: a test may still catch a defect no spell models. It costs as many casts as there are kills, so it is never the default, and it goes with `--all`, since a test that kills nothing in one file may be the only one that kills in another.

A run that stops before the end of its plan — no kalku is left to carry it — still reports what it cast: the wekufe it measured were measured. The summary carries why in `stopped`, the human report says `PARTIAL` above the score and `blocked: could not measure` below it, and the exit code is 2, so nothing reads a part as the whole.

followed by one summary object (counts, score, suppression changes).

**Hints are fixed templates per spell**, filled from the site, never generated by a model: deterministic, cheap, and honest about what kalku actually knows.

| Spell | Hint template |
|---|---|
| `arm` | no test fails without this clause of `<enclosing>`; assert what only it returns, in a case that takes it |
| `compare` | no test tells `<a> <op> <b>` apart at the boundary; add a case where they are equal |
| `connect` | no test has exactly one side of `<op>` true; add one for each side |
| `negate` | no test tells the two branches of this condition apart; assert what only one of them does |
| `literal` | no test depends on the exact value `<original>` |
| `call` | no test observes the effect of `<call>`; assert what it returns or changes |

### MCP

`kalku serve` also exposes the loop as MCP tools, so agents in Claude Code, Cursor, and similar hosts use it without shelling out:

| Tool | Does |
|---|---|
| `kalku_run(files, since?, limit?)` | run and return survivors in the agent format; `since` narrows to a change, `limit` caps the casts |
| `kalku_cast(wekufe[])` | re-cast and return outcomes |
| `kalku_show(wekufe)` | full detail of one |
| `kalku_propose_equivalent(wekufe, reason)` | write a **proposal** for a person to review; never applied |

The MCP layer is a client of the client protocol, like the CLI. It adds no capability the CLI lacks.

### Guardrails

An agent optimising for dead wekufe can reach the number without closing holes, with no ill intent. kalku makes those shortcuts visible or impossible:

- **Agents never suppress.** `kalku_propose_equivalent` writes to `.kalku/equivalent.proposed`, which kalku reads but never applies. Moving an entry into `.kalku/equivalent` is a human edit.
- **Suppression changes are loud.** New entries in `.kalku/equivalent` or wider `exclude` / `exclude_calls` in a PR are listed under their own heading in every report format (see *What blocks a PR*). The docs recommend CODEOWNERS on `.kalku/` and `.kalku.toml`.
- **A kill needs a test that passes on the original** (see *The loop*).
- **A timeout is not a kill.** A test that makes the wekufe hang does not kill it (see *Outcomes and score*).

Equivalence arguments stay proposals because they cannot be verified; tests that kill are accepted because they can. What can be verified is verified; what cannot stays a human decision.

### `kalku info`

`kalku info [topic] [--json]` documents spells, outcomes, formats, the equivalent-file syntax, and the guardrails, from the binary itself — the same pattern as `kai info`. It is the source of truth an agent consults instead of guessing.

`kalku info agents` prints a snippet ready to paste into a project's `CLAUDE.md` or `AGENTS.md`:

> After writing or changing tests, run `kalku run --since HEAD --format agent` and kill the survivors with tests that pass on the original code. Re-check with `kalku cast <id>`. Never edit `.kalku/equivalent`, `exclude`, or `exclude_calls`; propose equivalents with a reason instead.

## Later: property synthesis

kaikai synthesises property checks from protocol laws declared in the source. The equivalent for a target language is to derive properties from what the code already states — Elixir `@spec`s, protocol implementations, encode/decode pairs marked as inverses — and emit them as tests in the language's own property framework (StreamData), so they run like any other test and are measured by the same wekufe.

## Later: farm mode

`run {git: url, ref: sha}` against a remote server that clones and runs on bigger hardware or across machines. It executes third-party code remotely, so it requires container sandboxing and real authentication before it ships. Not part of the initial design.

## Open questions

- Editor integration: LSP diagnostics for survivors, or a lighter file-based report the editor watches.
