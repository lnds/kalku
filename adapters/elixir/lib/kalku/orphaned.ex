defmodule Kalku.Orphaned do
  @moduledoc """
  What a kalku does when the run that summoned it is gone.

  A run ends its kalku: it asks, and past that it kills the process and
  what the process started. A run that was itself killed does neither, and
  the kalku is left with a suite under way, or a cast, that nobody will
  read the result of. Its input reaching the end with no `shutdown` on it
  is how it knows, and it ends then, without finishing what it was doing.

  What it started ends with it. A runtime gives each program it starts a
  process group of its own, so they are found by whose children they are,
  not by the group the kalku leads.
  """

  @doc "Ends everything this runtime started, and then the runtime."
  @spec leave() :: no_return()
  def leave do
    Kalku.Strays.sweep()
    kill(started())
    System.halt(0)
  end

  @doc "The processes this runtime started, and theirs, deepest last."
  @spec started() :: [String.t()]
  def started do
    case System.cmd("ps", ["-axo", "pid=,ppid="], stderr_to_stdout: true) do
      {listed, 0} -> descendants(listed, List.to_string(:os.getpid()))
      _ -> []
    end
  rescue
    _ -> []
  end

  @doc "The descendants of `pid` in what `ps -axo pid=,ppid=` printed."
  @spec descendants(String.t(), String.t()) :: [String.t()]
  def descendants(listed, pid) do
    children =
      for line <- String.split(listed, "\n"),
          [child, parent] <- [String.split(line)],
          reduce: %{} do
        by_parent -> Map.update(by_parent, parent, [child], &[child | &1])
      end

    below(children, [pid], MapSet.new([pid]))
  end

  defp below(_children, [], _seen), do: []

  defp below(children, [pid | rest], seen) do
    found = children |> Map.get(pid, []) |> Enum.reject(&MapSet.member?(seen, &1))
    found ++ below(children, rest ++ found, Enum.into(found, seen))
  end

  # One command for all of them: the program a runtime starts its programs
  # through is among them, and after it nothing more can be started.
  defp kill([]), do: :ok

  defp kill(pids) do
    System.cmd("kill", ["-KILL" | pids], stderr_to_stdout: true)
  rescue
    _ -> :ok
  end
end
