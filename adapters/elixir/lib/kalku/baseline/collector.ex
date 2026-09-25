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
    defstruct id: nil, file: nil, line: nil, duration_ms: 0, failure: nil
  end

  @name __MODULE__

  @doc "Start an empty collector, replacing one a previous run left behind."
  def start do
    stop()
    Agent.start_link(fn -> [] end, name: @name)
  end

  @doc "Remember one finished test."
  def record(%Test{} = test), do: Agent.update(@name, &[test | &1])

  @doc "Every test the suite ran, in the order they are written."
  def taken, do: @name |> Agent.get(& &1) |> Enum.sort_by(&{&1.file, &1.line})

  @doc "Stop collecting; the next run starts from nothing."
  def stop do
    case Process.whereis(@name) do
      nil -> :ok
      pid -> Agent.stop(pid)
    end
  end
end
