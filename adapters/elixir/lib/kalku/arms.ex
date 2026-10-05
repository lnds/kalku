defmodule Kalku.Arms do
  @moduledoc """
  The `arm` spell: delete one clause of a `case`, `cond`, `receive`,
  `with … else`, or `fn`, or one clause of a multi-clause function.

  A clause is deleted as whole lines, so the site is only proposed when
  every clause starts its own line and the next clause (or the closing
  `end`) starts a later one. A lone clause is never deleted.
  """

  alias Kalku.{Source, Spells, Walker}

  @defs ~w(def defp defmacro defmacrop)a

  @doc "Arm candidates for a clause container node."
  def clauses({kind, meta, args}, ctx, src) when kind in [:case, :cond, :receive, :with] do
    kw = List.last(args)
    key = if kind == :with, do: :else, else: :do
    delete_each(List.wrap(Walker.kw(kw, key)), boundary(kw, key, meta), ctx, src)
  end

  def clauses({:fn, meta, arrows}, ctx, src),
    do: delete_each(arrows, get_in(meta, [:closing, :line]), ctx, src)

  def clauses(_node, _ctx, _src), do: []

  @doc "Arm candidates for the clauses of multi-clause functions in a module body."
  def def_clauses(block, closing, ctx, src) do
    block
    |> body_exprs()
    |> ending_last(closing, src)
    |> Enum.filter(&match?({def, _, [_ | _]} when def in @defs, &1))
    |> Enum.group_by(&signature/1)
    |> Enum.flat_map(fn {{name, arity}, defs} ->
      def_group(defs, "#{name}/#{arity}", ctx, src)
    end)
  end

  # ---- containers --------------------------------------------------------

  # The clauses under `key` end where the next block keyword (`after`,
  # `else`, …) starts, or at the closing `end`.
  defp boundary(kw, key, meta) do
    kw
    |> Enum.map(fn {k, _} -> k end)
    |> Enum.drop_while(&(key_name(&1) != key))
    |> Enum.drop(1)
    |> case do
      [next | _] -> next |> elem(1) |> Keyword.get(:line)
      [] -> end_line(meta)
    end
  end

  defp key_name({:__block__, _, [name]}), do: name
  defp key_name(name), do: name

  defp delete_each(arrows, end_line, ctx, src)
       when length(arrows) >= 2 and is_integer(end_line) do
    starts = Enum.map(arrows, &first_pos/1)

    if lines_ok?(starts, end_line, src) do
      bounds = Enum.map(starts, &elem(&1, 0)) ++ [end_line]

      bounds
      |> Enum.chunk_every(2, 1, :discard)
      |> Enum.map(&whole_lines(&1, Walker.enclosing(ctx)))
    else
      []
    end
  end

  defp delete_each(_arrows, _end, _ctx, _src), do: []

  # ---- functions ---------------------------------------------------------

  defp def_group(defs, fun, ctx, src) when length(defs) >= 2 do
    spans = Enum.map(defs, &def_lines/1)
    starts = Enum.map(defs, fn {_, meta, _} -> Spells.pos(meta) end)
    enclosing = Walker.enclosing(%{ctx | fun: fun})

    if Enum.all?(spans, & &1) and Enum.all?(starts, &(&1 && Source.line_start?(src, &1))),
      do: Enum.map(spans, fn {first, last} -> whole_lines([first, last + 1], enclosing) end),
      else: []
  end

  defp def_group(_defs, _fun, _ctx, _src), do: []

  defp def_lines({_, meta, _}) do
    last = get_in(meta, [:end, :line]) || get_in(meta, [:end_of_expression, :line])
    if last, do: {meta[:line], last}
  end

  # Before Elixir 1.18 the parser does not say where the last expression of
  # a block ends. It ends on the last line before the block's `end` that is
  # neither blank nor a comment. A line inside a string can look like either,
  # so the claim is checked: the expression ends on the first line from
  # there on at which it parses on its own.
  defp ending_last(exprs, closing, src) when is_integer(closing) do
    case List.pop_at(exprs, -1) do
      {{name, meta, args} = last, rest} when is_list(meta) ->
        if ends?(meta), do: exprs, else: rest ++ [{name, ended(last, closing, src) ++ meta, args}]

      _ ->
        exprs
    end
  end

  defp ending_last(exprs, _closing, _src), do: exprs

  defp ends?(meta), do: Keyword.has_key?(meta, :end) or Keyword.has_key?(meta, :end_of_expression)

  defp ended({_, meta, _}, closing, src) do
    first = meta[:line]
    from = last_code_line(src, closing - 1)

    last =
      is_integer(first) and Enum.find(max(from, first)..(closing - 1)//1, &whole?(src, first, &1))

    if last, do: [end_of_expression: [line: last]], else: []
  end

  defp last_code_line(src, n) when n >= 1 do
    case String.trim(Source.line(src, n)) do
      "" -> last_code_line(src, n - 1)
      "#" <> _ -> last_code_line(src, n - 1)
      _ -> n
    end
  end

  defp last_code_line(_src, _n), do: 0

  defp whole?(src, first, last),
    do: Source.parses?(Enum.map_join(first..last, "\n", &Source.line(src, &1)))

  defp signature({_, _, [head | _]}) do
    case head do
      {:when, _, [{name, _, args} | _]} -> {name, length(List.wrap(args))}
      {name, _, args} -> {name, length(List.wrap(args))}
    end
  end

  defp body_exprs({:__block__, _, exprs}) when is_list(exprs), do: exprs
  defp body_exprs(nil), do: []
  defp body_exprs(expr), do: [expr]

  # ---- shared ------------------------------------------------------------

  defp whole_lines([first, next], enclosing),
    do: %{spell: "arm", from: {first, 1}, to: {next, 1}, replacement: "", enclosing: enclosing}

  defp lines_ok?(starts, end_line, src) do
    lines = Enum.map(starts, &elem(&1, 0))

    Enum.all?(starts, &Source.line_start?(src, &1)) and
      lines == Enum.sort(Enum.uniq(lines)) and
      List.last(lines) < end_line
  end

  defp end_line(meta), do: get_in(meta, [:end, :line])

  # The earliest position mentioned anywhere in a clause's head, falling back
  # to the arrow itself.
  defp first_pos({:->, meta, [head, _body]}) do
    {_, found} =
      Macro.prewalk(head, [Spells.pos(meta)], fn
        {_, m, _} = node, acc when is_list(m) -> {node, [Spells.pos(m) | acc]}
        node, acc -> {node, acc}
      end)

    found |> Enum.reject(&is_nil/1) |> Enum.min()
  end
end
