defmodule Kalku.Baseline.Calls do
  @moduledoc """
  Which functions each test calls.

  A line is counted when the body written on it runs. A guard, a pattern or
  the head of a `case` arm is evaluated by calls that never get that far:
  the call that does not match is the one that tells `>` from `>=`, and it
  runs no line of the clause it was turned away from. Asked of lines alone,
  the test of a boundary is not among the tests of the comparison that
  draws it.

  So a test is also credited with every line of each function it called,
  whatever clause the call ended in, including none. The runtime counts
  calls itself, per function and for every process, with nothing to trace
  them to.

  Loading a module again forgets its counts and stops the counting — a
  mocking library does that to the modules it replaces. A module found that
  way has been called in ways nobody counted, so the test is credited with
  all of it, and the counting is started again.
  """

  @counting [:local, :call_count]

  @doc """
  Starts counting calls into these modules, and reads where their functions
  are written.
  """
  def start(modules) do
    watched = for module <- modules, count(module), do: {module, clauses_of(module)}
    Process.put(__MODULE__, %{watched: watched, before: %{}})
    :ok
  end

  @doc """
  The lines of every function called since this was last asked, as `{file,
  line}`.
  """
  def taken do
    case Process.get(__MODULE__) do
      nil ->
        []

      %{watched: watched, before: before} = state ->
        {lines, now} = Enum.reduce(watched, {[], before}, &called_in/2)
        Process.put(__MODULE__, %{state | before: now})
        lines
    end
  end

  @doc "Stops counting: a cast runs code that nobody is counting calls into."
  def stop do
    case Process.delete(__MODULE__) do
      nil -> :ok
      %{watched: watched} -> Enum.each(watched, fn {module, _} -> uncount(module) end)
    end
  end

  defp called_in({module, clauses}, {lines, before}) do
    counted = for {function, places} <- clauses, do: {function, places, calls(module, function)}

    if Enum.any?(counted, fn {_, _, n} -> n == :forgotten end) do
      # Counting again starts every function of the module from nothing.
      count(module)
      everything = Enum.flat_map(counted, fn {_, places, _} -> places end)

      {everything ++ lines,
       Map.drop(before, for({function, _, _} <- counted, do: {module, function}))}
    else
      Enum.reduce(counted, {lines, before}, fn {function, places, n}, {lines, before} ->
        {credit(n, Map.get(before, {module, function}, 0), places, lines),
         put(before, module, function, n)}
      end)
    end
  end

  defp credit(now, was, places, lines) when now > was, do: places ++ lines
  defp credit(_now, _was, _places, lines), do: lines

  # Most functions are never called by most tests: only the ones that were
  # are remembered, so asking again costs what was called and no more.
  defp put(before, _module, _function, 0), do: before
  defp put(before, module, function, n), do: Map.put(before, {module, function}, n)

  defp calls(module, {name, arity}) do
    case :erlang.trace_info({module, name, arity}, :call_count) do
      {:call_count, n} when is_integer(n) -> n
      _ -> :forgotten
    end
  rescue
    _ -> :forgotten
  end

  defp count(module) do
    is_integer(:erlang.trace_pattern({module, :_, :_}, true, @counting))
  rescue
    _ -> false
  end

  defp uncount(module) do
    :erlang.trace_pattern({module, :_, :_}, false, @counting)
  rescue
    _ -> 0
  end

  # ---- where a function is written ---------------------------------

  # Each function with the lines of its clauses. A clause runs from its
  # head to the last line anything in it is written on, and never into the
  # definition after it: a quoted expression carries the line it was quoted
  # on, which can be anywhere.
  defp clauses_of(module) do
    with {:ok, forms} <- forms_of(module),
         file when is_binary(file) <- source_of(module, forms) do
      written = written_in(forms)
      starts = written |> Enum.map(fn {_, first, _} -> first end) |> Enum.sort() |> Enum.uniq()

      written
      |> Enum.group_by(fn {function, _, _} -> function end, fn {_, first, last} ->
        for line <- first..min(last, before_next(starts, first))//1, do: {file, line}
      end)
      |> Enum.map(fn {function, places} ->
        {function, places |> List.flatten() |> Enum.uniq()}
      end)
    else
      _ -> []
    end
  end

  defp before_next(starts, first) do
    case Enum.find(starts, &(&1 > first)) do
      nil -> :infinity
      next -> next - 1
    end
  end

  # A function quoted from another file says so in a `file` attribute ahead
  # of it. Its lines are lines of that file, and nobody casts a wekufe there.
  defp written_in(forms) do
    {written, _, _} =
      Enum.reduce(forms, {[], nil, nil}, fn
        {:attribute, _, :file, {now, _}}, {written, own, _} ->
          {written, own || now, now}

        {:function, _, name, arity, clauses}, {written, own, own} ->
          {spans(clauses, {name, arity}) ++ written, own, own}

        _, acc ->
          acc
      end)

    for {function, {first, last}} <- written, do: {function, first, last}
  end

  defp spans(clauses, function),
    do: for(clause <- clauses, spanned = span(clause), do: {function, spanned})

  defp span({:clause, anno, _, _, _} = clause) do
    case :erl_anno.line(anno) do
      first when is_integer(first) and first > 0 -> {first, max(first, last_line(clause, first))}
      _ -> nil
    end
  end

  defp last_line(node, seen) when is_tuple(node) do
    node
    |> Tuple.to_list()
    |> case do
      [_tag, anno | rest] -> last_line(rest, max(seen, line_in(anno)))
      other -> last_line(other, seen)
    end
  end

  defp last_line(nodes, seen) when is_list(nodes),
    do: Enum.reduce(nodes, seen, fn node, seen -> last_line(node, seen) end)

  defp last_line(_leaf, seen), do: seen

  # An annotation is a line, a line and a column, or a list of what is known
  # about the place. Anything else in second position is not one.
  defp line_in(line) when is_integer(line), do: line
  defp line_in({line, column}) when is_integer(line) and is_integer(column), do: line

  defp line_in([{key, _} | _] = anno) when is_atom(key) do
    case Keyword.get(anno, :location) do
      line when is_integer(line) -> line
      {line, _} when is_integer(line) -> line
      _ -> 0
    end
  end

  defp line_in(_), do: 0

  defp forms_of(module) do
    with {:file, beam} when is_binary(beam) or is_list(beam) <- :cover.is_compiled(module),
         {:ok, {_, [abstract_code: {_, forms}]}} <- :beam_lib.chunks(beam, [:abstract_code]) do
      {:ok, forms}
    else
      _ -> :error
    end
  end

  defp source_of(module, forms) do
    case module.module_info(:compile)[:source] do
      nil ->
        with {:attribute, _, :file, {file, _}} <-
               Enum.find(forms, &match?({:attribute, _, :file, _}, &1)),
             do: to_string(file)

      source ->
        to_string(source)
    end
  rescue
    _ -> nil
  end
end
