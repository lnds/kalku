defmodule Kalku.Deps do
  @moduledoc """
  Which files have to be recompiled when one changes.

  A module that uses another's macro is stitched into it at compile time:
  changing the macro and reloading only its own module leaves the caller
  holding the old expansion. The site would then be measured against code
  nobody is running.

  The graph comes from `mix xref`, which is the compiler's own answer
  rather than a guess of ours. Its output is captured instead of printed,
  because stdout carries the protocol.
  """

  @doc """
  The files that depend on this one at compile time.

  Empty for the ordinary case, which is most sites: a function call is a
  runtime dependency, and reloading the module it lives in is enough.
  """
  def compile_dependents(file) do
    case ask_xref(file) do
      {:ok, output} -> dependents_in(output, file)
      :error -> []
    end
  end

  @doc "True when changing this file means recompiling others."
  def dependents?(file), do: compile_dependents(file) != []

  @doc "The reload a site in this file needs: `dependents` when others are stitched into it."
  def reload_for(file), do: if(dependents?(file), do: "dependents", else: "module")

  # `xref graph --sink F` prints the files that depend on F as roots, with
  # F itself indented beneath each of them.
  defp dependents_in(output, file) do
    for line <- String.split(output, "\n"),
        trimmed = String.trim_trailing(line),
        trimmed != "",
        not indented?(trimmed),
        trimmed != file,
        do: trimmed
  end

  defp indented?(line), do: String.match?(line, ~r/^[\s│├└]/u)

  defp ask_xref(file) do
    capture(fn ->
      Mix.Task.rerun("xref", ["graph", "--label", "compile", "--sink", file])
    end)
  rescue
    _ -> :error
  catch
    _, _ -> :error
  end

  # Two things have to be undone for the length of the question. The
  # kalku runs mix quietly, so a quiet shell would answer nothing at all;
  # and a task writes to the group leader, which is where the answer is
  # caught rather than printed, because printing it is printing onto the
  # protocol.
  defp capture(work) do
    {:ok, io} = StringIO.open("")
    original_leader = Process.group_leader()
    original_shell = Mix.shell()

    Process.group_leader(self(), io)
    Mix.shell(Mix.Shell.IO)

    try do
      work.()
    after
      Mix.shell(original_shell)
      Process.group_leader(self(), original_leader)
    end

    {:ok, {_input, output}} = StringIO.close(io)
    {:ok, output}
  end
end
