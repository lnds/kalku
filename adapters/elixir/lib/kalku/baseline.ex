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

  alias Kalku.Baseline.{Collector, Cover, Recorder}

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
         {:ok, modules} <- load(root, files) do
      if measuring, do: Cover.reset()
      ExUnit.run(modules)
      # What the suite as a whole reached. Per-test attribution has to add
      # up to this, and where it does not, something swallowed it.
      whole = if measuring, do: MapSet.new(Cover.covered()), else: MapSet.new()
      tests = Collector.taken()
      measured = if measuring, do: attributed(tests, modules, whole), else: tests
      Collector.stop()
      Cover.stop()
      Kalku.Runtime.mark(Mix.Project.config()[:app])
      report(measured, System.monotonic_time(:millisecond) - started, root, opts)
    end
  end

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
  whole reached; otherwise withholds all of it, and says why on stderr.
  """

  def reconcile(measured, whole) do
    attributed = for t <- measured, l <- t.lines, into: MapSet.new(), do: l
    lost = MapSet.difference(whole, attributed)

    if MapSet.size(lost) == 0 do
      measured
    else
      IO.puts(:stderr, unattributable(lost))
      for t <- measured, do: %{t | lines: []}
    end
  end

  defp unattributable(lost) do
    where =
      lost
      |> Enum.map(fn {file, line} -> "#{Path.basename(file)}:#{line}" end)
      |> Enum.sort()
      |> Enum.take(4)
      |> Enum.join(", ")

    "kalku: per-test coverage is not reportable for this run. " <>
      "The suite reached #{MapSet.size(lost)} line(s) that no single test is " <>
      "credited with (#{where}), so the attribution is incomplete — a module " <>
      "replaced while the suite ran, as a mocking library does, is the usual " <>
      "cause. Every wekufe will be cast against the whole suite instead, so " <>
      "this run is slower and every survivor is real."
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
    for test <- tests do
      Cover.reset()
      run_only(test, modules)
      %{test | lines: Cover.covered()}
    end
  end

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

    ExUnit.run(modules)
  after
    ExUnit.configure(exclude: [], include: [])
  end

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
  defp report(tests, duration_ms, root, opts) do
    named = for t <- tests, do: {test_id(t.file, t.line, root), t}

    failures = for {id, t} <- named, t.failure != nil, do: %{"test" => id, "message" => t.failure}

    timings =
      for {id, t} <- named,
          do: %{"test" => id, "file" => relative(t.file, root), "duration_ms" => t.duration_ms}

    body = %{
      "status" => if(failures == [], do: "green", else: "red"),
      "duration_ms" => duration_ms,
      "tests" => timings,
      "failures" => failures
    }

    {:ok, Map.merge(body, coverage_field(coverage(named, root), opts))}
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

  # Big coverage goes to a file in the reni rather than through the pipe:
  # a megabyte of JSON per worker is a cost the protocol lets us decline.
  defp coverage_field([], _opts), do: %{}

  defp coverage_field(entries, opts) do
    limit = Keyword.get(opts, :inline_limit_bytes, 65_536)
    encoded = Kalku.Json.encode(entries)

    if byte_size(encoded) <= limit do
      %{"coverage" => entries}
    else
      %{"coverage_path" => spill(encoded, Keyword.get(opts, :reni, Mix.Project.build_path()))}
    end
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
