defmodule Kalku.Cast.Entered do
  @moduledoc """
  Whether the tests of a cast ran the function the wekufe is in.

  A wekufe is cast in a runtime that has already started. Code that runs
  once — as the application starts, or the first time something is asked
  for — has run by then and does not run again, so a wekufe in it is
  loaded and never executed. No test can kill what never runs, whatever
  the test asserts, and calling that a survivor reports a hole where there
  may be none.

  So the calls into the wekufe's function are counted while its tests
  run. The runtime counts them itself, for every process, which matters:
  a callback is called by the process that owns it, not by the test.

  Only a count of zero that can be trusted says anything. Loading a module
  again stops the counting — a mocking library does that — and a cast whose
  count was lost is left as the tests judged it.
  """

  @counting [:local, :call_count]

  @doc """
  Starts counting calls into the function this site is in, among the
  modules the wekufe compiled to. Returns what to ask afterwards, or `nil`
  when the site is in no function that can be counted: the body of a
  module, or a macro, runs when the code is compiled.
  """
  def watch(compiled, site) do
    with {module, name} <- named(site["enclosing"]),
         true <- List.keymember?(compiled, module, 0),
         [_ | _] = functions <- arities(module, name) do
      for function <- functions, do: :erlang.trace_pattern(function, true, @counting)
      functions
    else
      _ -> nil
    end
  end

  @doc """
  True when some test called the function, false when none did, and
  `:unknown` when the count was not kept to the end.
  """
  def entered?(nil), do: :unknown

  def entered?(functions) do
    counts = Enum.map(functions, &calls/1)

    cond do
      Enum.any?(counts, &(&1 == :forgotten)) -> :unknown
      Enum.all?(counts, &(&1 == 0)) -> false
      true -> true
    end
  end

  @doc "Stops the counting."
  def stop(nil), do: :ok

  def stop(functions) do
    for function <- functions,
        do: safely(fn -> :erlang.trace_pattern(function, false, @counting) end)

    :ok
  end

  @doc "What to say of a wekufe no test ran."
  def unrun(site) do
    "no test ran `#{site["enclosing"]}` while the wekufe was in it. Either no test reaches " <>
      "it, or it is code that runs once, as the application starts or the first time it is " <>
      "asked for, and a wekufe is cast in a runtime where that has already happened."
  end

  # `Mod.Sub.name/arity`, as a site names the declaration it is in. A site
  # in the body of a module names the module alone.
  defp named(enclosing) when is_binary(enclosing) do
    case Regex.run(~r/^(.+)\.([^.\/]+)\/\d+$/, enclosing) do
      [_, module, name] -> {Module.concat([module]), String.to_existing_atom(name)}
      _ -> nil
    end
  rescue
    # A name nothing has is the name of no function.
    ArgumentError -> nil
  end

  defp named(_), do: nil

  # Every arity: a default argument makes a function of each, and a call
  # to one of them is a call to what the site is in.
  defp arities(module, name) do
    for {^name, arity} <- module.module_info(:functions), do: {module, name, arity}
  rescue
    _ -> []
  end

  defp calls(function) do
    case safely(fn -> :erlang.trace_info(function, :call_count) end) do
      {:call_count, n} when is_integer(n) -> n
      _ -> :forgotten
    end
  end

  defp safely(work) do
    work.()
  rescue
    _ -> :error
  catch
    _, _ -> :error
  end
end
