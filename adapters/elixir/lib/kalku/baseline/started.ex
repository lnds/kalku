defmodule Kalku.Baseline.Started do
  @moduledoc """
  Which of the project's functions run as its application starts.

  The application starts once, before any test, so what it runs then is
  reached by no test and by every one of them: each test stands on what
  those functions built. Counted with lines alone, such code reads as code
  nothing reaches, and a wekufe in it is never cast.

  The calls are counted by the runtime while the applications start, and
  the functions that were called are remembered. Every test is credited
  with their lines where no test reaches them by itself, so a wekufe there
  is cast, against the whole suite, which is what judges it.
  """

  alias Kalku.Baseline.Calls

  @called {__MODULE__, :called}
  @counting [:local, :call_count]

  @doc "Starts counting calls into the project's modules, loading them to do it."
  def watch(app) do
    modules = for module <- modules_of(app), counted?(module), do: module
    Process.put(__MODULE__, modules)
    :ok
  end

  @doc "Remembers the functions called since `watch/1`, and stops counting."
  def seen do
    modules = Process.delete(__MODULE__) || []
    called = for module <- modules, function <- called_in(module), do: {module, function}
    for module <- modules, do: uncount(module)
    :persistent_term.put(@called, called)
    :ok
  end

  @doc "The lines of the functions that ran as the application started, as `{file, line}`."
  def lines do
    @called
    |> :persistent_term.get([])
    |> Enum.group_by(fn {module, _} -> module end, fn {_, function} -> function end)
    |> Enum.flat_map(fn {module, functions} -> Calls.written(module, functions) end)
  end

  @doc """
  Credits every test with the lines that ran at start and that no test
  reaches by itself. A line some test does reach is left to the tests that
  reach it.
  """
  def credit(tests) do
    reached = for t <- tests, line <- t.lines, into: MapSet.new(), do: line

    case Enum.reject(lines(), &MapSet.member?(reached, &1)) do
      [] -> tests
      only_at_start -> for t <- tests, do: %{t | lines: t.lines ++ only_at_start}
    end
  end

  defp modules_of(app) do
    ebin = Path.join([Mix.Project.build_path(), "lib", to_string(app), "ebin"])

    for beam <- Path.wildcard(Path.join(ebin, "*.beam")),
        module = beam |> Path.basename(".beam") |> String.to_atom(),
        match?({:module, _}, :code.ensure_loaded(module)),
        do: module
  end

  defp counted?(module) do
    is_integer(:erlang.trace_pattern({module, :_, :_}, true, @counting))
  rescue
    _ -> false
  end

  defp uncount(module) do
    :erlang.trace_pattern({module, :_, :_}, false, @counting)
  rescue
    _ -> 0
  end

  defp called_in(module) do
    for {name, arity} <- module.module_info(:functions),
        match?({:call_count, n} when is_integer(n) and n > 0, count_of(module, name, arity)),
        do: {name, arity}
  rescue
    _ -> []
  end

  defp count_of(module, name, arity) do
    :erlang.trace_info({module, name, arity}, :call_count)
  rescue
    _ -> :undefined
  end
end
