defmodule Kalku.Baseline.Collector do
  @moduledoc """
  Holds what the suite did, for as long as it takes to report it.

  It exists because an ExUnit formatter does not: ExUnit stops its
  formatters when the suite ends, so whatever they kept would be gone
  before anyone could read it. The collector is started before the suite
  and stopped after the results are taken.
  """

  use Agent

  defmodule Test do
    @moduledoc "One test, as the protocol needs it."
    defstruct id: nil, module: nil, file: nil, line: nil, duration_ms: 0, failure: nil, lines: []
  end

  @name __MODULE__

  @doc """
  Start an empty collector, replacing one a previous run left behind.

  It also carries whether this run measures coverage: ExUnit takes
  formatters as bare modules, so the formatter has nowhere else to be
  told.
  """
  def start(measuring) do
    stop()
    Agent.start_link(fn -> %{tests: [], measuring: measuring} end, name: @name)
  end

  @doc "True when this run is counting lines."
  def measuring?, do: Agent.get(@name, & &1.measuring)

  @doc "Remember one finished test, and the lines it executed."
  def record(%Test{} = test, lines),
    do: Agent.update(@name, &%{&1 | tests: [%{test | lines: lines} | &1.tests]})

  @doc "Every test the suite ran, in the order they are written."
  def taken, do: @name |> Agent.get(& &1.tests) |> Enum.sort_by(&{&1.file, &1.line})

  @doc "Stop collecting; the next run starts from nothing."
  def stop do
    case Process.whereis(@name) do
      nil -> :ok
      pid -> Agent.stop(pid)
    end
  end
end
