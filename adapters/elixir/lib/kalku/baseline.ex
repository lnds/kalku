defmodule Kalku.Baseline do
  @moduledoc """
  Running the project's suite once, before any wekufe exists.

  The baseline is what every later measurement is read against. A suite
  that is already red cannot tell anyone whether a wekufe was noticed, so
  a red baseline is reported as red and the run stops there rather than
  producing a score nobody should believe.

  The suite runs inside this kalku's own runtime, not in a `mix test`
  subprocess: a subprocess would take its results with it and leave the
  runtime cold for the casts that follow.
  """

  alias Kalku.Baseline.{Calls, Collector, Cover, Packed, Recorder}

  @doc """
  Runs the suite and reports what it did.

  Returns `{:ok, map}` shaped like `baseline_done`, or `{:error, code,
  message}` when the suite could not be run at all — which is not the same
  as a suite that ran and failed.
  """
  def run(root, opts \\ []) do
    started = System.monotonic_time(:millisecond)
    measuring = coverage?(opts)

    with {:ok, files} <- test_files(root),
         :ok <- start_exunit(measuring),
         {:ok, modules} <- load(root, files),
         {:ok, {measured, withheld}} <- through(modules, measuring) do
      Kalku.Runtime.mark(Mix.Project.config()[:app])
      elapsed = System.monotonic_time(:millisecond) - started
      report(measured, withheld, elapsed, root, opts)
    end
  end

  # The suite is run by ExUnit, and ExUnit is something a test can take
  # down: a project that stops applications on its way out, reached from a
  # test, stops the one running it. That is a suite that could not be run,
  # and it is said like any other, not left for the pipe closing to say.
  defp through(modules, measuring) do
    {:ok, ran(modules, measuring)}
  rescue
    e -> could_not_run(Exception.message(e))
  catch
    kind, reason -> could_not_run(Exception.format(kind, reason, __STACKTRACE__))
  after
    Collector.stop()
    Cover.stop()
  end

  defp ran(modules, measuring) do
    if measuring, do: Cover.reset()
    seen_through(modules)
    # What the suite as a whole reached. Per-test attribution has to add
    # up to this, and where it does not, something swallowed it.
    whole = if measuring, do: MapSet.new(Cover.covered()), else: MapSet.new()
    tests = Collector.taken()

    if measuring and judged_by_some?(tests),
      do: attributed(tests, modules, whole),
      else: {tests, nil}
  end

  @doc """
  Runs these modules, and raises unless ExUnit said the run was over.
  """
  def seen_through(modules) do
    Collector.again()
    # Stopped under the run before this one, it is started for this one.
    {:ok, _} = Application.ensure_all_started(:ex_unit)
    ExUnit.run(modules)
    (Collector.ended?() and standing?()) || raise "ExUnit stopped before it had run them all"
  end

  # ExUnit can be stopped under a run and still see it to an end of sorts,
  # with the tests it lost left out of what it reports.
  defp standing?, do: is_pid(Process.whereis(ExUnit.Server))

  defp could_not_run(why) do
    {:error, "baseline_failed",
     "the suite stopped being run before it ended: " <> Kalku.Baseline.first_lines(why)}
  end

  @doc "The head of a failure: what went wrong, without the whole stack under it."
  def first_lines(text),
    do:
      text |> String.split("\n", trim: true) |> Enum.take(3) |> Enum.map_join(" ", &String.trim/1)

  @doc """
  Loads the suite into this runtime without running it.

  What `prepare` owes a kalku that will only ever be asked to cast: the
  modules a cast runs against are the ones loaded here, and a kalku in a
  pool is never asked for a baseline — the kaikai side asks one kalku for
  it and gives every worker the answer. Without this, such a worker has no
  suite to judge with, and a kalku with nothing to judge with must not be
  the one to say a wekufe survived.
  """
  def load_suite(root) do
    with {:ok, files} <- test_files(root),
         :ok <- start_exunit(false),
         {:ok, _modules} <- load(root, files) do
      :ok
    end
  end

  # A suite in which nothing passes judges nothing: which tests reach a line
  # is asked to choose the tests of a cast, and there will be none. Running
  # every test again to learn it is time spent before saying so.
  #
  # One with tests that pass is measured with those, and every test is run
  # again, the failing ones too: the lines only they reach are lines the
  # suite reached, and left uncredited they would read as attribution that
  # went missing. Which tests judge is for the kaikai side to say.
  defp judged_by_some?(tests), do: Enum.any?(tests, &(&1.failure == nil))

  # Coverage this kalku cannot stand behind is not reported at all.
  #
  # Running the suite whole and then each test alone measures the same
  # thing twice, so the two have to agree: every line the suite reached,
  # some test reached. A line the suite executed that no single test is
  # credited with is attribution that went missing — which is what happens
  # when a mocking library replaces one of the project's own modules and
  # `:cover` loses what it instrumented.
  #
  # kalku cannot tell a lost line from a line no test truly reaches, and
  # must not guess: reporting the attribution anyway judges each wekufe
  # against too few tests, and a wekufe no test was aimed at survives. A
  # survivor that is not a hole is the one thing a run must never produce.
  #
  # So the whole attribution is withheld and every wekufe faces the whole
  # suite: slower, and true. The protocol already has this — a kalku
  # without `per_test_coverage` works exactly this way.
  defp attributed(tests, modules, whole), do: reconcile(measure_each(tests, modules), whole)

  @doc """
  Keeps the per-test attribution only when it adds up to what the suite as a
  whole reached; otherwise withholds all of it. Returns the tests and, when
  it withheld, why: the reader of a report in which every wekufe faced the
  whole suite is owed the reason, and only the kalku has it.
  """
  def reconcile(measured, whole) do
    attributed = for t <- measured, l <- t.lines, into: MapSet.new(), do: l
    lost = MapSet.difference(whole, attributed)

    if MapSet.size(lost) == 0 do
      {measured, nil}
    else
      why = unattributable(lost)
      IO.puts(:stderr, "kalku: per-test coverage is not reportable for this run. " <> why)
      {for(t <- measured, do: %{t | lines: []}), why}
    end
  end

  defp unattributable(lost) do
    where =
      lost
      |> Enum.map(fn {file, line} -> "#{Path.basename(file)}:#{line}" end)
      |> Enum.sort()
      |> Enum.take(4)
      |> Enum.join(", ")

    "The suite reached #{MapSet.size(lost)} line(s) that no single test is credited with " <>
      "(#{where}), so which tests reach a line cannot be stood behind. What does " <>
      "this: a module replaced while the suite ran, as a mocking library does; " <>
      "something set up the first time it is asked for, and not again; a process of " <>
      "the project's own that runs on its own clock, between two tests. " <>
      "Every wekufe is cast against the whole suite instead: slower, and every " <>
      "survivor is real."
  end

  @doc """
  Runs each test again on its own, to learn which lines it executes.

  A second pass, and deliberately so: `:cover` counts per line rather
  than per test, and ExUnit's formatter events arrive after the fact, so
  the only honest attribution is one test at a time. It costs a second
  run of the suite, once, and buys casting each wekufe against the few
  tests that reach its line instead of all of them.
  """
  def measure_each(tests, modules) do
    started = now()
    Cover.reset()
    Calls.start(:cover.modules())

    {measured, _} =
      Enum.map_reduce(tests, %{done: 0, of: length(tests), said: started, spent: {0, 0, 0}}, fn
        test, progress ->
          {running, _} = :timer.tc(fn -> run_only(test, modules) end)
          {finding, entered} = :timer.tc(&Cover.entered/0)

          {reading, lines} =
            :timer.tc(fn ->
              lines = Cover.covered(entered)
              Cover.reset(entered)
              Enum.uniq(Calls.taken() ++ lines)
            end)

          {%{test | lines: lines}, told(progress, {running, finding, reading}, started)}
      end)

    Calls.stop()
    measured
  end

  @every_ms 10_000

  # A large suite takes minutes here, and a run that says nothing for
  # minutes cannot be told from one that hangs. Where the time went is said
  # too, because it is the first thing asked of a pass that is slow.
  defp told(progress, {running, finding, reading}, started) do
    {ran, found, read} = progress.spent

    progress = %{
      progress
      | done: progress.done + 1,
        spent: {ran + running, found + finding, read + reading}
    }

    if now() - progress.said >= @every_ms do
      IO.puts(:stderr, so_far(progress, now() - started))
      %{progress | said: now()}
    else
      progress
    end
  end

  defp so_far(%{done: done, of: total, spent: {ran, found, read}}, elapsed_ms) do
    "kalku: per-test coverage: #{done} of #{total} tests (#{div(done * 100, total)}%) in " <>
      "#{div(elapsed_ms, 1000)}s — #{div(ran, 1_000_000)}s running them, " <>
      "#{div(found, 1_000_000)}s finding the modules they entered, " <>
      "#{div(read, 1_000_000)}s reading their lines"
  end

  defp now, do: System.monotonic_time(:millisecond)

  # By file and line together, the way `mix test path:LINE` does. By line
  # alone, every test written on that line of any file runs too, and each
  # of them is then credited with the lines the others executed — an
  # attribution kalku would not be able to stand behind.
  defp run_only(test, modules) do
    ExUnit.configure(
      exclude: [:test],
      include: [{:location, {test.file, test.line}}],
      max_cases: 1
    )

    seen_through(holding(test, modules))
  after
    ExUnit.configure(exclude: [], include: [])
  end

  @doc """
  The modules ExUnit is handed to run one test: the one it is written in.

  ExUnit walks every module it is given and announces each of their tests,
  excluded or not. Handed the whole suite once per test, that is the
  suite's size squared.
  """
  def holding(%{module: nil}, modules), do: modules
  def holding(%{module: module}, _modules), do: [module]

  # Coverage is what lets a wekufe be cast against the few tests that
  # reach its line instead of the whole suite, but `:cover` counts per
  # line and not per test, so it is bought with a serial suite. A project
  # can decline it and pay in casts instead.
  defp coverage?(opts) do
    if Keyword.get(opts, :coverage, true) do
      case Cover.start(Mix.Project.config()[:app]) do
        {:ok, n} when n > 0 ->
          true

        other ->
          IO.puts(:stderr, "kalku: coverage off: #{inspect(other)}")
          false
      end
    else
      false
    end
  end

  @doc "The test id the protocol uses: where the test is written."
  def test_id(file, line, root), do: "#{relative(file, root)}:#{line}"

  # A test is named by where it is written, relative to the project: an
  # absolute path would name this machine, and every kalku in the pool
  # would call the same test something different.
  defp report(tests, withheld, duration_ms, root, opts) do
    named = for t <- tests, do: {test_id(t.file, t.line, root), t}

    failures = for {id, t} <- named, t.failure != nil, do: %{"test" => id, "message" => t.failure}

    timings =
      for {id, t} <- named,
          do: %{"test" => id, "file" => relative(t.file, root), "duration_ms" => t.duration_ms}

    body = %{
      "status" => if(failures == [], do: "green", else: "red"),
      "duration_ms" => duration_ms,
      "tests" => timings,
      "failures" => failures,
      "differences" => differences()
    }

    {:ok,
     body |> Map.merge(coverage_field(named, root, opts)) |> Map.merge(withheld_field(withheld))}
  end

  @doc """
  How this run of the suite is unlike `mix test` in the project.

  What a test can tell, and nothing else: a suite that is green under
  `mix test` and red here is red for one of these, and the reader is owed
  which they are before going to look.
  """
  def differences do
    [
      "the build is in #{Mix.Project.build_path()} (MIX_BUILD_PATH), not in `_build`: " <>
        "a test that names `_build`, or looks there for what a build leaves, " <>
        "fails here and passes under `mix test`",
      "the kalku loads `test/test_helper.exs` and the test files itself: " <>
        "an alias the project gives `mix test`, and what the alias sets up first, is not run"
    ]
  end

  @doc """
  Which tests execute each line, as the protocol reports it.

  A line nobody runs has no entry at all: the question is which tests
  cover a line, and for an uncovered line the honest answer is none,
  which the kaikai side reads as `no_coverage` rather than as a hole.
  """
  def coverage(named, root) do
    named
    |> Enum.flat_map(fn {id, t} ->
      for {file, line} <- t.lines, do: {{relative(file, root), line}, id}
    end)
    |> Enum.group_by(fn {place, _} -> place end, fn {_, id} -> id end)
    |> Enum.sort()
    |> Enum.map(fn {{file, line}, ids} ->
      %{"file" => file, "line" => line, "tests" => Enum.sort(Enum.uniq(ids))}
    end)
  end

  defp withheld_field(nil), do: %{}
  defp withheld_field(why), do: %{"coverage_withheld" => why}

  # The least a test's name takes on a line: enough to know, without writing
  # it out, that coverage naming this many will not fit through the pipe.
  @bytes_a_name 12

  # Small coverage goes through the pipe as it is. The rest goes to a file
  # in the reni, packed: a megabyte of JSON per worker is a cost the
  # protocol lets us decline, and written line by line a large project's
  # coverage is the same few thousand names a hundred megabytes over.
  defp coverage_field(named, root, opts) do
    limit = Keyword.get(opts, :inline_limit_bytes, 65_536)
    reached = for {id, t} <- named, do: {id, placed(t.lines, root)}

    case Packed.references(reached) do
      0 -> %{}
      n when n * @bytes_a_name > limit -> packed(reached, opts)
      _ -> inline_or_packed(coverage(named, root), reached, limit, opts)
    end
  end

  defp inline_or_packed(entries, reached, limit, opts) do
    if byte_size(Kalku.Json.encode(entries)) <= limit do
      %{"coverage" => entries}
    else
      packed(reached, opts)
    end
  end

  defp packed(reached, opts) do
    encoded = reached |> Packed.pack() |> Kalku.Json.encode()
    reni = Keyword.get(opts, :reni, Mix.Project.build_path())
    %{"coverage_packed_path" => spill(encoded, reni)}
  end

  # A file is named once for all its lines: making a path relative is not
  # free, and a test reaches thousands of lines of the same few files.
  defp placed(lines, root) do
    names =
      for file <- lines |> Enum.map(&elem(&1, 0)) |> Enum.uniq(),
          into: %{},
          do: {file, relative(file, root)}

    for {file, line} <- lines, do: {Map.fetch!(names, file), line}
  end

  defp spill(encoded, reni) do
    path = Path.join(reni, "coverage.json")
    File.mkdir_p!(Path.dirname(path))
    File.write!(path, encoded)
    path
  end

  # A project with no tests is not a project kalku can measure: every
  # wekufe would survive, and the score would say the suite is worthless
  # when there is no suite at all.
  defp test_files(root) do
    case Path.wildcard(Path.join(root, "test/**/*_test.exs")) do
      [] -> {:error, "no_tests", "no test files under #{Path.join(root, "test")}"}
      files -> {:ok, Enum.sort(files)}
    end
  end

  # `autorun: false` before anything else: a project's `test_helper.exs`
  # calls `ExUnit.start/1` itself, and the default would queue the suite to
  # run again when this process exits.
  defp start_exunit(measuring) do
    {:ok, _} = Collector.start(measuring)
    ExUnit.start(autorun: false, formatters: [Recorder])
    :ok
  rescue
    e -> {:error, "baseline_failed", "ExUnit would not start: #{Exception.message(e)}"}
  end

  @modules {__MODULE__, :test_modules}

  @doc "The test modules this kalku has loaded, for a cast to run against."
  def loaded_modules, do: :persistent_term.get(@modules, [])

  @doc "Forget the loaded suite, so the next baseline reads the files again."
  def forget, do: :persistent_term.erase(@modules)

  # The modules are kept, not just loaded, for two reasons that both bite.
  # ExUnit takes the suite it is given and does not keep it for a second
  # run; and a file already required loads no modules the second time. A
  # kalku runs the suite once per wekufe, not once per life, so it
  # remembers what it loaded. `reload` is what invalidates this.
  defp load(root, files) do
    case :persistent_term.get(@modules, nil) do
      nil -> load_files(root, files)
      modules -> {:ok, modules}
    end
  end

  @doc """
  Leaves out what was registered to run when the suite ends.

  A library that sets itself up — a mocking library copies the modules it
  will replace — tidies up after itself in an `after_suite` callback, and
  ExUnit runs those at the end of every `ExUnit.run`. This runtime runs the
  suite again and again and stays warm, so that tidying would leave the next
  run with nothing set up: a test that passes alone fails, and a failing
  test is a kill. A wekufe identical to the original came back `killed` for
  that reason. The callbacks are left out; the runtime is thrown away when
  the run is over.

  A callback is registered from `test_helper.exs` or from a test's own
  `setup`, so they are left out once the suite is loaded and again each time
  it has run (`Kalku.Baseline.Recorder`).
  """
  def keep_suite_state, do: Application.put_env(:ex_unit, :after_suite, [])

  defp load_files(root, files) do
    helper = Path.join(root, "test/test_helper.exs")
    if File.exists?(helper), do: Code.require_file(helper)

    case Kernel.ParallelCompiler.require(files, return_diagnostics: true) do
      {:ok, modules, _info} ->
        keep_suite_state()
        {:ok, remember(test_modules(modules))}

      {:error, errors, _info} ->
        {:error, "baseline_failed", load_failure(errors)}
    end
  rescue
    e -> {:error, "baseline_failed", "the suite would not load: #{Exception.message(e)}"}
  catch
    :exit, reason -> {:error, "baseline_failed", "the suite would not load: #{inspect(reason)}"}
  end

  defp test_modules(modules), do: Enum.filter(modules, &function_exported?(&1, :__ex_unit__, 0))

  defp remember(modules) do
    :persistent_term.put(@modules, modules)
    modules
  end

  defp load_failure(errors) do
    errors
    |> Enum.map_join("; ", fn
      {file, line, message} -> "#{Path.basename(to_string(file))}:#{line}: #{message}"
      other -> inspect(other)
    end)
    |> case do
      "" -> "the suite would not load"
      text -> text
    end
  end

  # Relative to the root the client named, and failing that to the
  # directory the kalku is actually standing in. They differ whenever a
  # path reaches the project through a symlink — `/tmp` is one on macOS —
  # and a coverage entry that stays absolute matches no site, which makes
  # every wekufe look uncovered.
  defp relative(file, root) do
    text = to_string(file)

    case Path.relative_to(text, Path.expand(root)) do
      ^text -> Path.relative_to(text, File.cwd!())
      relative -> relative
    end
  end
end
