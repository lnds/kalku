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

  alias Kalku.Baseline.{Collector, Recorder}

  @doc """
  Runs the suite and reports what it did.

  Returns `{:ok, map}` shaped like `baseline_done`, or `{:error, code,
  message}` when the suite could not be run at all — which is not the same
  as a suite that ran and failed.
  """
  def run(root) do
    started = System.monotonic_time(:millisecond)

    with {:ok, files} <- test_files(root),
         :ok <- start_exunit(),
         {:ok, modules} <- load(root, files) do
      ExUnit.run(modules)
      tests = Collector.taken()
      Collector.stop()
      report(tests, System.monotonic_time(:millisecond) - started, root)
    end
  end

  @doc "The test id the protocol uses: where the test is written."
  def test_id(file, line, root), do: "#{relative(file, root)}:#{line}"

  # A test is named by where it is written, relative to the project: an
  # absolute path would name this machine, and every kalku in the pool
  # would call the same test something different.
  defp report(tests, duration_ms, root) do
    named = for t <- tests, do: {test_id(t.file, t.line, root), t}

    failures = for {id, t} <- named, t.failure != nil, do: %{"test" => id, "message" => t.failure}

    timings =
      for {id, t} <- named,
          do: %{"test" => id, "file" => relative(t.file, root), "duration_ms" => t.duration_ms}

    {:ok,
     %{
       "status" => if(failures == [], do: "green", else: "red"),
       "duration_ms" => duration_ms,
       "tests" => timings,
       "failures" => failures
     }}
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
  defp start_exunit do
    ExUnit.start(autorun: false, formatters: [Recorder])
    {:ok, _} = Collector.start()
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

    case Kernel.ParallelCompiler.require(files) do
      {:ok, modules, _warnings} -> {:ok, remember(test_modules(modules))}
      {:error, errors, _warnings} -> {:error, "baseline_failed", load_failure(errors)}
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
