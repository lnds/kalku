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
- **The reni** lives under `$XDG_CACHE_HOME/kalku/<project-hash>/`. It holds build artefacts and caches, never source copies: source is read from the working tree, read-only.

### One binary, two lifetimes

The CLI and the server are the same code. `kalku run` connects to a running server, or starts one. `kalku run --ci` runs the orchestrator in-process for exactly one run and exits. A CLI run is a server whose lifetime is one run, so nothing is written twice.

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

The loop, in the reni: `prepare` copies the project there (without `target` or `.git`) and builds every test executable with `cargo test --no-run`; a build that fails is `prepare_failed`, with the compiler's own words. `baseline` lists each executable's tests and runs each one alone, because stable libtest has neither per-test timings nor JSON. A test's id is its file and name, `src/lib.rs::tests::one_is_inside`, so it is stable and unique across packages. `cast` splices the wekufe into the copy, rebuilds, runs the listed tests grouped by executable, and restores the file whatever happened: a build error is `compile_error`, a failing test is `killed`, and a requested test that did not pass or did not report is never a survivor. Cargo gets only the environment the run needs. It answers `abort`: the line is read on a thread of its own while the cast runs, cargo and everything it started (a test binary is cargo's grandchild) are killed with their process group, the file is put back, and the same kalku goes on to the next cast. The kaikai side recycles it only if it says it could not restore.

Where the toolchain has the LLVM tools (the rustup component `llvm-tools`, found in the toolchain's own directory), the kalku also announces `per_test_coverage`. The baseline then builds the project a second time with `-C instrument-coverage`, into a target directory of its own, runs each test alone with its own profile files, and asks LLVM which lines each one executed; the map goes inline or, past `inline_limit_bytes`, into a JSON file in the reni. A cast then runs only the tests that reach the wekufe's line, and a line no test reaches is `no_coverage`, not a survivor. Without the tools the capability is simply not claimed and every cast runs the whole suite. Measured on the kalku's own crate (16 sites in one file, 6 workers): the baseline costs more (114 s against 42 s, the second build and a profile read per test), each cast costs less (9 to 11 tests instead of 170; the cast phase went from 131 s to 68 s), so it pays from a few dozen sites on.

Its spells are the shared ones, with Rust's own judgement about where each is worth proposing:

| Spell | Rust forms |
|---|---|
| `arm` | delete a `match` arm — only when a catch-all remains, because without one the wekufe is not exhaustive and the compiler, not a test, is what rejects it |
| `compare` | `>=`↔`>`, `<`↔`<=`, `==`↔`!=`, in expressions and in match guards |
| `connect` | `&&`↔`\|\|` — not in a `let` chain, where `\|\|` cannot bind |
| `negate` | `if c` → `if !(c)`, and drop a `!` |
| `literal` | `n`→`n+1` for plain decimals within their type's range, `true`↔`false`, a non-empty string → `""` |

`call` is not offered: dropping a call in Rust almost never keeps the types, so it would propose compile errors and little else, and each of those costs a build. Nothing is proposed in tests, in `unsafe fn`, in patterns, types or attributes (a literal there is structure, not a value), or in the arguments of a macro, which are tokens the parser does not read.

### The Python kalku

Python has mutation frameworks (`mutmut`, `cosmic-ray`), and the rule above says to wrap a framework where one exists. As with Rust, kalku does not: the driver path is not implemented, and what makes kalku worth running — the shared spells and their hints, `exclude_calls`, equivalents keyed to the enclosing function, per-test selection by coverage, the gate on changed lines — needs the kalku to own the loop. The Python kalku is native, in the shape of the others, and written in Python.

- **It runs inside the project's own interpreter.** The tests need the project's packages and its pytest, so the summoning picks the active environment, then `.venv` or `venv`, then `python3` (`KALKU_PYTHON` overrides). It therefore uses the standard library and nothing else, and ships as a single zipapp (`kalku-python`), so it never chooses which version of anything the project gets. It needs Python 3.12 or later, for `sys.monitoring`.
- **Sites come from `ast` and `tokenize`**, never from a scan of the text: `ast` gives every node's span, and the tokens give where an operator sits between two operands, which `ast` does not keep. Columns are bytes in `ast` and characters in the protocol, so one module turns between them.
- **A cast forks.** The parent is the warm part: it has pytest imported and, after a baseline, what the tests import from outside the project. Each run of the suite happens in a forked child that is thrown away, so there is no state to reset, an `abort` is a kill of the child's process group, and a wekufe cannot leak into the next. The wekufe is applied in memory: the importer serves the changed module from a string, so no file is written and no bytecode cache can serve the original. Nothing uses threads, because forking a process that has them is how a child inherits a lock nobody will release.
- **Coverage is `sys.monitoring`**, cleared before each test and read after it. What ran at import — a function's header, its decorators and defaults, a module's constants — ran before any test started, so it is credited to the tests that run what it belongs to, from the syntax tree.
- **pytest is configured by the project.** Its `addopts` select what is run (`click` deselects thirty thousand stress tests with one) and are kept, minus what cannot run here: other processes (`-n`, `--dist`) and another coverage tool (`--cov*`).

| Spell | Python forms |
|---|---|
| `arm` | delete a `case` of a `match`, whole |
| `compare` | `>=`↔`>`, `<=`↔`<`, `==`↔`!=`, `is`↔`is not`, `in`↔`not in` |
| `connect` | `and`↔`or`, one operator at a time in a chain |
| `negate` | `not x` becomes `x`, and a condition `c` becomes `not (c)` (`if`, `elif`, `while`, a conditional expression) |
| `literal` | `n`→`n+1` for plain decimals, `True`↔`False`, a non-empty string → `""` |
| `call` | a call that is a whole statement, awaited or not, becomes `pass` |

Not proposed: docstrings and bare strings, module dunders (`__all__`, `__version__`), annotations, the type a `cast(...)` or `TypeVar(...)` names, `if TYPE_CHECKING:`, `if __name__ == "__main__":`, the patterns of a `match`, the arguments of a call `exclude_calls` names, and test files. Only `arm` can leave a file that does not parse, so only its candidates are parsed before anyone is asked to cast them.

### The Java kalku

Java has a mutation framework, PIT, and it is a good one. kalku does not wrap it, for the reasons it does not wrap `cargo-mutants`: the driver path is not implemented, and nearly all of PIT's commits come from a single person, who also sells its commercial extension. The Java kalku is native, in the shape of the others, and written in Java.

What a Java tool has to survive is the JDK itself: a release every six months, each with a new class-file version and often new syntax. A tool that mutates bytecode needs its bytecode library taught each of them. This kalku is built on one rule instead: **it reads and writes nothing whose format changes with the JDK, except through the project's own JDK.**

- **It runs on the project's own JDK**, Java 11 or later, as the Python kalku runs in the project's interpreter. Its own code is compiled for Java 11 and has no dependencies, so it never chooses which version of anything the project gets. Java 8 is not supported: a jar built for 11 does not load there, so the summoning script reads the version first and refuses by name, rather than let the kalku die with a class-version error and a closed pipe.
- **Sites come from `javac`**, the compiler that will build the project, through its public tree API (`com.sun.source`, in the module `jdk.compiler`) and nothing private to one release. The grammar is therefore always the project's own. A file this JDK cannot parse is skipped with the compiler's own words; the parser recovers from an error and still returns a tree, and a tree it had to guess at is never searched.
- **A construct newer than Java 11 is reached without naming it.** The kalku is compiled for 11, so it cannot refer to the type of a switch expression or of a pattern. The running JDK's own tree scanner walks them, and the kalku tells them apart by the name of their kind. The same jar finds the cases of a `switch` expression on Java 17 and the guard of a pattern on Java 21.
- **A runtime with no compiler** — a JRE, or an image cut down with `jlink` — is answered in the protocol, `toolchain_missing`, with the reason.
- **The channel is bytes.** Lines are read and written as UTF-8 on the real descriptors, whatever the platform's charset is, because the JDK's default has not been the same on every release; `System.out` is pointed at stderr. No real is ever encoded: the shortest text of a double is not the same on every JDK.

It finds sites and casts them, in a single-module Maven project whose tests run on the JUnit Platform (JUnit 5 or later). A project of several modules, or one with JUnit 4 or TestNG alone, is refused in `prepare`, by name. `kalku init` takes a `pom.xml` for a Java project and writes a summoning that starts `kalku-java`, which ships as one file: the script that picks the JDK and turns away one that is too old, with the jar behind it.

The loop, in the reni:

- **`prepare` copies the project and lets Maven build the copy.** Maven writes `target/` beside the pom, so it never runs in the user's tree. kalku's own files (`.kalku.toml`, `.kalku/`) are not copied, and the checks a build makes beyond compiling — licence headers, style, the repository's history — are switched off by the names their plugins give: the copy is not the project as its owner keeps it, and a build that checks every file for a licence header failed on kalku's own. The copy is synced by content, and a changed file is stamped with the time of the copy, because Maven goes by times; when nothing changed, and the same JDK built what is there, Maven is not started again. One Maven run compiles the sources and the tests and writes down two things: the class path the tests run with, and the project as Maven resolved it, from which the kalku reads what the build tells `javac` (release, encoding, `-parameters`, `--enable-preview`, the annotation processors it names) and what it tells the tests' JVM (surefire's `argLine` and system properties). Everything Maven prints goes to a file.
- **The JUnit launcher is fetched to fit the project.** The project's test class path has JUnit's engines; what starts them is not there, because Maven's own test runner brings it. The kalku has Maven fetch the launcher at the version of the project's engines, and compiles its test runner — which travels in the jar as source — in the reni, with the project's JDK, against that launcher. There is no version of JUnit the kalku was built for: the same runner is compiled against JUnit 5.4 and against 6.
- **Tests never run in the kalku's own JVM.** Each run, the baseline's and every cast's, is a JVM of its own that is thrown away. A wekufe that calls `System.exit`, that takes all the memory, or that leaves something in a static field cannot touch the kalku or the next cast, so `dirty` is always false. The child's environment is a short list of what a build needs plus what `hello` gave; its output goes to a file; it starts with `-XX:+ExitOnOutOfMemoryError`; and it ends when its standard input closes, which is when the kalku ends, however that happens.
- **A test is a method**, `com.acme.ParserTest#reads(int, String)`. The invocations of a parameterized test and the tests a factory makes are counted as the method that gives them, and a `@Nested` class is in the file of the class around it. Each result is counted from the listener's own events, never from a summary.
- **A cast compiles one file, in memory.** The site is spliced into the text of the reni's copy, and the project's own `javac`, running inside the kalku, compiles that one file against the classes Maven built, into a directory of the cast's own that goes first on the tests' class path. No source file is ever written.
- **A wekufe in a constant is compiled into everything.** `javac` copies a `static final` constant into every class that uses it, the tests included. Compiling the one file would leave the old value in the others: a wekufe a test does notice would come back as a survivor, and a test that names the constant would fail for no reason. A site in what a `final` field is set to is therefore marked `reload: "dependents"`, and its cast compiles every source and every test of the project again.

What is not a verdict is never given one. A test JVM that ended before its tests did — `System.exit`, out of memory, a crash — did not fail an assertion, so it is not a kill; a cast in which none of the selected tests ran and passed is not a survivor. Both are answered with a fatal `error`, which the kaikai side records as `crashed`, with the reason. And `compile_error` means the wekufe does not compile: when a compile fails, the same file is compiled unchanged the same way, once, and if that fails too the kalku has failed to reproduce the build and says so, rather than count every wekufe in the file as caught.

Measured on the fixture project (2 source files, 5 tests, 14 sites, Java 21): `prepare` takes 3.9 s, nearly all of it Maven; the baseline 0.6 s; a cast 0.67 s on average, of which about 0.6 s is starting a JVM and JUnit. That last number is the price of a JVM for each cast and the first thing to bring down, with a test JVM that stays warm between casts; it was chosen first because it is the one arrangement in which nothing a wekufe does can outlive it.

Not read from the build yet: surefire's `includes` and `excludes` (the default class names are used, so `*IT` classes are left to integration tests), the environment variables it sets, and what an annotation processor itself depends on (the processor's own jar is found, by the version the build states or manages).

| Spell | Java forms |
|---|---|
| `arm` | delete a `case` of a `switch`, statement or expression — only while a `default` remains, because without one a switch that has to be exhaustive no longer is, and the compiler, not a test, is what rejects it |
| `compare` | `>=`↔`>`, `<=`↔`<`, `==`↔`!=` |
| `connect` | `&&`↔`\|\|` |
| `negate` | a `!` is dropped, and the condition `c` of an `if` becomes `!(c)` |
| `literal` | `n`→`n+1` for plain decimal `int` and `long` literals within their type's range, `true`↔`false`, a non-empty string or text block → `""`; literals joined by `+` are one constant to the compiler, and one site |
| `call` | a call that is a whole statement becomes the empty statement `;`, so what follows it is still what follows it |

Not proposed: annotations and the default of an annotation's element; `serialVersionUID`, which no test of the program can tell apart; the labels of a `case`, whose literals are structure (its guard and its body are code); a condition that binds a pattern variable (`o instanceof String s && …`), where negating or reconnecting it changes what is in scope; `this(...)` and `super(...)`; a call in the header of a `for` or as the whole body of a `case … ->`, where no empty statement can stand; hexadecimal, octal, binary and underscored numbers; the arguments of a call that `exclude_calls` names; test sources, known by where Maven and Gradle keep them and by how test classes are named.

`enclosing` names a method with its package, its classes and the types of its parameters as the source writes them — `com.acme.Parser.next(int,List<String>)` — because overloads with the same number of parameters are ordinary in Java. A constructor is `<init>`, and an anonymous class is `$1`, `$2`, counted within its top-level class.

### The kaikai kalku

kaikai already has `kai mutate`, split the same way the protocol is: `kaic2 --mutate-list-json` catalogues sites from the AST, `kaic2 --mutate-apply <i>` writes the spliced source, and a shell driver owns the run loop. The kaikai kalku is a thin native kalku over those flags:

- `sites` → `--mutate-list-json`;
- `cast` → `--mutate-apply`, write the wekufe into the reni, build, run the covering tests with `kai test`;
- `code_hash` → a hash of the emitted code, which is deterministic in kaikai.

kaikai compiles to native code and cannot hot-load, so "warm" here means a warm compiler and build cache, not a warm runtime; the kalku does not announce `hot_load`.

This kalku needs from kaikai: the full site data in `--mutate-list-json` (span, original and replacement text, enclosing declaration, ordinal), and machine-readable results plus test selection in `kai test`. Without the latter it degrades to whole-suite runs with no `killed_by`.

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

- **Green or red.** A red suite aborts the run: mutating on top of failing tests measures nothing.
- **Coverage map:** `(file, line) → [test ids]`.
- **Durations** per test, from which each wekufe's timeout is derived (`max(3 × covering tests' baseline, floor)`).

The baseline is cached by the hashes of all source and test files; an unchanged project reuses it.

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

After the run the session stays in `ready`. On the next run, the server hashes the source files, tells each kalku to reload only what changed, reuses coverage for untouched tests, and re-casts only the wekufe whose cache key changed. `kalku run --watch` does this on every save.

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
| `no_coverage` | no test executes the site | reported apart |
| `compile_error` | the wekufe does not compile | excluded |
| `crashed` | the cast produced no verdict: the kalku died, it could not run the tests it was given, or the wekufe held more memory than the ceiling (the message says how much) | reported apart |
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
threshold = 0.80        # global floor; --ci exits 1 below it
ratchet = true          # the global score may not drop below the stored baseline

[elixir]
partition_env = "MIX_TEST_PARTITION"
```

## Caching

Cache entries live in the reni. A wekufe outcome's key is:

`hash(adapter version, spell, site span, source file hash, hashes of the covering tests' files, hashes of the files those tests load)`

Outcomes are also indexed by the compiled wekufe's hash, so identical programs reached from different sites share one cast.

Any change to a key component invalidates it. Keys never use timestamps.

## Running in CI

`kalku run --ci` runs the orchestrator in-process for one run: no server, no socket.

### Two cadences

| When | Command | Purpose |
|---|---|---|
| every PR | `kalku run --ci --since origin/<base>` | block the merge if the change introduces holes |
| nightly | `kalku run --ci --all --shard i/n`, then `kalku merge` | track the global score |

A full run on every PR does not scale: a medium Elixir project yields thousands of wekufe.

### What blocks a PR

A PR fails when **it introduces a hole**: a wekufe that survives on a line the PR changed, or a site on one that no test reaches at all. Code no test ran is the larger hole — there was nothing even to fail to notice it. Gating PRs on the global score punishes whoever touches a file with old debt, and teams switch such tools off. The global score is guarded separately by `[score] threshold` and, with `ratchet = true`, by a stored baseline it may not drop below.

Changes to suppressions are surfaced, never silent: if a PR adds entries to `.kalku/equivalent` or widens `exclude`/`exclude_calls` in `.kalku.toml`, the report and the GitHub summary list them under their own heading. Hiding a hole must be as visible as leaving one.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | measured; nothing to report |
| `1` | measured; survivors on changed lines, sites on changed lines that no test reaches, or the score is below threshold or baseline |
| `2` | could not measure: red baseline, failed prepare, orphan equivalent entry, config error |

"Your tests have holes" and "kalku could not run" are different failures and CI must be able to tell them apart.

### Sharding

`--shard i/n` takes a deterministic partition of the planned wekufe (by `site_id`), so shards never overlap and reruns pick the same work. Each shard writes a JSON report; `kalku merge` combines them into one score and one report.

### Cache

The reni is saved between builds (e.g. `actions/cache` on `~/.cache/kalku`, keyed by the hash of `mix.lock` and restored by prefix). Baseline, coverage, and unchanged outcomes are reused, so incremental runs work in CI too.

### Output

`--format github` emits an annotation on each survivor's line with the wekufe's diff, and a summary to `$GITHUB_STEP_SUMMARY`. `--format json` is the stable machine-readable report used by `kalku merge`.

### External resources

Kalku run in parallel, so each needs its own external state. For Elixir with Postgres, `prepare` creates one test database per kalku through `MIX_TEST_PARTITION`; the CI job provides the server as a service container.

```yaml
- uses: actions/cache@v4
  with:
    path: ~/.cache/kalku
    key: kalku-${{ hashFiles('mix.lock') }}-${{ github.sha }}
    restore-keys: kalku-${{ hashFiles('mix.lock') }}-
- run: kalku run --ci --since origin/${{ github.base_ref }} --format github
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

followed by one summary object (counts, score, suppression changes).

**Hints are fixed templates per spell**, filled from the site, never generated by a model: deterministic, cheap, and honest about what kalku actually knows.

| Spell | Hint template |
|---|---|
| `arm` | no test reaches this clause of `<enclosing>`; add a case that takes it |
| `compare` | no test tells `<a> <op> <b>` apart at the boundary; add a case where they are equal |
| `connect` | no test has exactly one side of `<op>` true; add one for each side |
| `negate` | no test takes the other branch of this condition |
| `literal` | no test depends on the exact value `<original>` |
| `call` | no test observes the effect of `<call>`; assert what it returns or changes |

### MCP

`kalku serve` also exposes the loop as MCP tools, so agents in Claude Code, Cursor, and similar hosts use it without shelling out:

| Tool | Does |
|---|---|
| `kalku_run(scope)` | run and return survivors in the agent format |
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
