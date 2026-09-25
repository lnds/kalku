defmodule Kalku.Cast.Tests do
  @moduledoc """
  Running exactly the tests a cast was given, and no others.

  The kaikai side chose them from the baseline's coverage: they are the
  tests that execute the line the wekufe changed. Running the rest would
  cost time and could not change the answer.

  The run stops at the first failure. One test noticing is what a kill
  is; the others would only say it again.
  """

  alias Kalku.Baseline.Collector

  @doc """
  Runs these tests against whatever is loaded, and reads the outcome.

  `killed` names the test that noticed. `survived` means every test the
  kaikai side thought relevant ran and none of them did.
  """
  def run(root, ids) do
    modules = Kalku.Baseline.loaded_modules()

    cond do
      modules == [] -> %{outcome: "survived", message: "no test modules are loaded"}
      ids == [] -> %{outcome: "survived", message: "no test covers this line"}
      true -> measure(root, ids, modules)
    end
  end

  defp measure(root, ids, modules) do
    {:ok, _} = Collector.start(false)
    select(root, ids)
    ExUnit.run(modules)
    ran = Collector.taken()
    Collector.stop()
    reset()

    case Enum.find(ran, &(&1.failure != nil)) do
      nil ->
        ran_anything(ran, ids)

      failed ->
        %{outcome: "killed", killed_by: Kalku.Baseline.test_id(failed.file, failed.line, root)}
    end
  end

  # A wekufe that no test even reached did not survive scrutiny; it was
  # never looked at. Saying `survived` there would count a hole nobody
  # measured as a hole somebody measured.
  defp ran_anything([], ids),
    do: %{outcome: "survived", message: "none of #{length(ids)} selected test(s) ran"}

  defp ran_anything(_ran, _ids), do: %{outcome: "survived"}

  # ExUnit selects by file and line, the way `mix test path:line` does, so
  # two tests written on the same line of different files stay apart.
  defp select(root, ids) do
    ExUnit.configure(
      exclude: [:test],
      include: Enum.map(ids, &location(root, &1)),
      max_cases: 1,
      max_failures: 1
    )
  end

  defp location(root, id) do
    case String.split(id, ":") do
      [file, line] -> {:location, {Path.expand(Path.join(root, file)), String.to_integer(line)}}
      _ -> {:location, {id, 0}}
    end
  end

  defp reset, do: ExUnit.configure(exclude: [], include: [], max_failures: :infinity)
end
