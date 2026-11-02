defmodule Kalku.Returns do
  @moduledoc """
  The `return` spell: a function clause gives back `nil` instead of what it
  computed.

  Elixir has no `return`; a clause's result is its last expression, so the
  site is that expression, replaced whole. Nothing is proposed where the
  question would be asked better elsewhere or would measure something
  other than the suite:

    * a result that already is `nil`;
    * a literal the `literal` spell perturbs, which asks the sharper
      question of whether a test depends on that exact value;
    * a `raise`, `throw` or `exit`, which returns nothing;
    * a callback marked `@impl`, whose result is a behaviour's protocol
      (`{:ok, state}`, `{:reply, reply, state}`): `nil` there crashes the
      process for every test that reaches it, which says the callback ran,
      not that its result was looked at — the values inside the tuple are
      what other spells reach;
    * a macro, whose result is code, not a value.
  """

  alias Kalku.{Spells, Walker}

  @no_value ~w(raise reraise throw exit)a

  @doc "The `return` candidate of one `def`/`defp` clause, or none."
  def result({_def, meta, [_head, body]}, ctx, src) when is_list(body) do
    with expr when expr != nil <- last(Walker.kw(body, :do)),
         true <- answers?(expr),
         from when from != nil <- start(expr),
         to when to != nil <- ending(expr, from, limit(meta, from), src) do
      [
        %{
          spell: "return",
          from: from,
          to: to,
          replacement: "nil",
          enclosing: Walker.enclosing(ctx)
        }
      ]
    else
      _ -> []
    end
  end

  def result(_def, _ctx, _src), do: []

  @doc "The `{name, arity}` of every function a module body marks `@impl`."
  def impls(exprs) do
    exprs
    |> Enum.reduce({false, MapSet.new()}, fn
      {:@, _, [{:impl, _, _}]}, {_, found} ->
        {true, found}

      {def, _, [head | _]}, {true, found} when def in [:def, :defp] ->
        {false, MapSet.put(found, signature(head))}

      _, acc ->
        acc
    end)
    |> elem(1)
  end

  @doc "The `{name, arity}` of a clause head."
  def signature({:when, _, [call | _]}), do: signature(call)
  def signature({name, _, args}) when is_atom(name), do: {name, length(List.wrap(args))}
  def signature(_), do: nil

  defp last({:__block__, _, [_, _ | _] = exprs}), do: List.last(exprs)
  defp last(expr), do: expr

  defp answers?({:__block__, _, [nil]}), do: false
  defp answers?({form, _, _}) when form in @no_value, do: false
  defp answers?({:__block__, _, [_]} = lit), do: Spells.literal_text(lit) == nil
  defp answers?(_), do: true

  # Where an expression starts: its own position, or for an operator or a
  # remote call, that of its leftmost operand.
  defp start({{:., _, [left, _]}, _, _}), do: start(left)
  defp start({:__aliases__, meta, _}), do: Spells.pos(meta)

  defp start({op, meta, [left, _]} = node) when is_atom(op) do
    if Macro.operator?(op, 2),
      do: start(left),
      else: Spells.pos(elem(node, 1)) || Spells.pos(meta)
  end

  defp start({:__block__, meta, [list]}) when is_list(list), do: Spells.pos(meta)
  defp start({_, meta, _}), do: Spells.pos(meta)
  defp start(_), do: nil

  # A clause body ends at its `end`, or for `do:` at the end of the `def`.
  defp limit(meta, {line, _}) do
    get_in(meta, [:end, :line]) || get_in(meta, [:end_of_expression, :line]) || line + 200
  end

  # The parser does not say where an expression ends on every version the
  # kalku runs on, so the end is found by parsing: the shortest text from
  # the start that parses to the same expression.
  #
  # No text that stops short of a token the expression holds parses to it,
  # so the search starts at the last position its metadata names.
  defp ending(expr, {line, col}, limit, src) do
    want = strip(expr)
    {last_line, last_col} = max({line, col}, last_named(expr))

    Enum.find_value(last_line..limit//1, fn l ->
      text = Kalku.Source.line(src, l)

      first =
        cond do
          l == last_line -> graphemes_before(text, last_col) + 1
          l == line -> col
          true -> 1
        end

      text
      |> cuts(first)
      |> Enum.find_value(fn cut ->
        text = Kalku.Source.slice(src, {line, col}, {l, cut})
        if same?(text, want), do: {l, cut}
      end)
    end)
  end

  # Metadata columns count codepoints, and the cuts count graphemes.
  defp graphemes_before(text, col),
    do: text |> String.codepoints() |> Enum.take(col - 1) |> Enum.join() |> String.length()

  # Columns on a line after which an expression may end: past a character
  # that is not a space, where a space, a comment, or the line's end follows.
  defp cuts(text, first) do
    chars = String.graphemes(text)
    after_each = Enum.drop(chars, 1) ++ [nil]

    for {{c, next}, i} <- Enum.with_index(Enum.zip(chars, after_each), 1),
        i >= first,
        c not in [" ", "\t"],
        next in [nil, " ", "\t", "#", ")", ",", ";", "]", "}"],
        do: i + 1
  end

  defp last_named(expr) do
    {_, last} =
      Macro.prewalk(expr, {0, 0}, fn
        {_, meta, _} = node, last when is_list(meta) ->
          named = [meta, meta[:end], meta[:closing]] |> Enum.map(&position/1)
          {node, Enum.max([last | named])}

        node, last ->
          {node, last}
      end)

    last
  end

  defp position([_ | _] = meta) do
    case {meta[:line], meta[:column]} do
      {line, col} when is_integer(line) and is_integer(col) -> {line, col}
      {line, _} when is_integer(line) -> {line, 1}
      _ -> {0, 0}
    end
  end

  defp position(_), do: {0, 0}

  defp same?(text, want) do
    case Code.string_to_quoted(text,
           columns: true,
           literal_encoder: &Kalku.Sites.encode_literal/2
         ) do
      {:ok, ast} -> strip(ast) == want
      _ -> false
    end
  end

  defp strip(ast), do: Macro.prewalk(ast, &Macro.update_meta(&1, fn _ -> [] end))
end
