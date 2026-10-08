defmodule Kalku.Baseline.Recorder do
  @moduledoc """
  An ExUnit formatter that reports nothing and remembers everything.

  ExUnit's own formatters write to stdout, which here carries the
  protocol, so the suite runs with this one instead: it prints nothing and
  forwards what each test was — where it is written, how long it took, and
  why it failed — to a collector that outlives the suite.

  It does not measure coverage. ExUnit delivers these events
  asynchronously, so a `test_started` can arrive after the test it
  announces has already run: clearing counters here clears the *next*
  test's lines. Coverage is measured by running each test on its own,
  in `Kalku.Baseline`.

  The split matters: ExUnit starts a formatter when the suite starts and
  stops it when the suite ends, so anything the formatter kept for itself
  dies with it, before the results can be read.
  """

  use GenServer

  alias Kalku.Baseline.Collector

  @impl true
  def init(_opts), do: {:ok, %{}}

  @impl true
  def handle_cast({:test_finished, %ExUnit.Test{} = test}, state) do
    if ran?(test), do: Collector.record(record(test), [])
    Kalku.Strays.sweep()
    {:noreply, state}
  end

  # ExUnit stops its formatters, and waits for them, before it reads the
  # `after_suite` callbacks: one registered while the suite ran is left out
  # here and never runs.
  def handle_cast({:suite_finished, _times}, state) do
    Collector.ended()
    Kalku.Baseline.keep_suite_state()
    Kalku.Strays.sweep()
    {:noreply, state}
  end

  def handle_cast(_event, state), do: {:noreply, state}

  # ExUnit announces a test it skipped or excluded as finished, like any
  # other. Counting those as tests that ran is what lets a cast whose
  # selection matched nothing report every wekufe as a survivor, and it is
  # what makes a skipped test look like a guard in a baseline.
  defp ran?(%ExUnit.Test{state: {:skipped, _}}), do: false
  defp ran?(%ExUnit.Test{state: {:excluded, _}}), do: false
  defp ran?(%ExUnit.Test{}), do: true

  defp record(%ExUnit.Test{} = test) do
    file = to_string(test.tags[:file])
    line = test.tags[:line]

    %Collector.Test{
      id: "#{file}:#{line}",
      module: test.module,
      file: file,
      line: line,
      duration_ms: div(test.time || 0, 1000),
      failure: failure_message(test)
    }
  end

  # A failure is quoted from the test's own words: what ExUnit would have
  # printed, without the colours, the header, or the stack — the assertion
  # and its two sides are what tells someone what broke.
  defp failure_message(%ExUnit.Test{state: {:failed, failures}} = test) do
    test
    |> ExUnit.Formatter.format_test_failure(failures, 1, :infinity, fn _kind, msg -> msg end)
    |> String.split("\n", trim: true)
    |> Enum.map(&String.trim/1)
    |> Enum.drop(1)
    |> Enum.take_while(&(&1 != "stacktrace:"))
    |> Enum.take(8)
    |> Enum.join("\n")
    |> String.trim()
  rescue
    # Saying "the test failed" here would be a lie of omission: the test
    # did fail, and kalku is the one that could not say how.
    e -> "kalku could not read this failure: #{Exception.message(e)}"
  end

  # A test whose module could not be set up was never run, and is not a test
  # that passed: counted as one, a suite that cannot start is green and a
  # wekufe that breaks `setup_all` survives every test it stopped.
  defp failure_message(%ExUnit.Test{state: {:invalid, module}}) do
    "its module could not be set up, so it did not run" <> why(module)
  end

  defp failure_message(_test), do: nil

  defp why(%{state: {:failed, failures}} = module) do
    module
    |> ExUnit.Formatter.format_test_all_failure(failures, 1, :infinity, fn _kind, msg -> msg end)
    |> String.split("\n", trim: true)
    |> Enum.map(&String.trim/1)
    |> Enum.drop(1)
    |> Enum.take_while(&(&1 != "stacktrace:"))
    |> Enum.take(8)
    |> Enum.join("\n")
    |> String.trim()
    |> case do
      "" -> ""
      said -> ":\n" <> said
    end
  rescue
    _ -> ""
  end

  defp why(_module), do: ""
end
