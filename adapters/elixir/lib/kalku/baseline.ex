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
      ExUnit.run(modules)
      tests = Collector.taken()
      measured = if measuring, do: measure_each(tests, modules), else: tests
      Collector.stop()
      Cover.stop()
      report(measured, System.monotonic_time(:millisecond) - started, root, opts)
    end
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
      run_only(test.line, modules)
      %{test | lines: Cover.covered()}
    end
  end

  # ExUnit selects by line the way `mix test path:LINE` does.
  defp run_only(line, modules) do
    ExUnit.configure(exclude: [:test], include: [line: line], max_cases: 1)
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
    encoded = JSON.encode!(entries)

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

  defp load_files(root, files) do
    helper = Path.join(root, "test/test_helper.exs")
    if File.exists?(helper), do: Code.require_file(helper)

    case Kernel.ParallelCompiler.require(files, return_diagnostics: true) do
      {:ok, modules, _info} -> {:ok, remember(test_modules(modules))}
      {:error, errors, _info} -> {:error, "baseline_failed", load_failure(errors)}
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

  defp relative(file, root), do: Path.relative_to(to_string(file), Path.expand(root))
end
