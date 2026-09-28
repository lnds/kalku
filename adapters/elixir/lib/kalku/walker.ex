defmodule Kalku.Walker do
  @moduledoc """
  Walks a module's AST and collects spell candidates, keeping the context
  a site depends on: the enclosing module and function, and whether the
  node sits in a pattern, a guard, a pipe's right side, an excluded call,
  or text that only reaches a `raise`.
  """

  alias Kalku.{Arms, Concurrency, Spells}

  defstruct module: [],
            fun: nil,
            pattern: false,
            guard: false,
            pipe_rhs: false,
            no_literal: false,
            spells: [],
            exclude: []

  # Module attributes whose values are docs, typespecs, or compiler
  # directives: no behaviour to break there.
  @skip_attrs ~w(moduledoc doc typedoc spec type typep opaque callback macrocallback impl
                 behaviour derive deprecated since compile dialyzer external_resource
                 on_load before_compile after_compile vsn)a

  @skip_forms ~w(quote alias import require use defstruct defexception defoverridable
                 defdelegate defprotocol)a

  @defs ~w(def defp defmacro defmacrop)a

  @doc "Candidates in `ast` for the given spells, skipping calls matched by `exclude`."
  def candidates(ast, src, spells, exclude) do
    walk(ast, %__MODULE__{spells: spells, exclude: exclude}, src)
  end

  @doc "Enclosing declaration name for a context, e.g. `MyApp.Parser.next_token/2`."
  def enclosing(%__MODULE__{module: [], fun: nil}), do: nil
  def enclosing(%__MODULE__{module: mod, fun: nil}), do: Enum.join(mod, ".")
  def enclosing(%__MODULE__{module: [], fun: fun}), do: fun
  def enclosing(%__MODULE__{module: mod, fun: fun}), do: Enum.join(mod, ".") <> "." <> fun

  # ---- traversal ---------------------------------------------------------

  defp walk({:defmodule, _, [{:__aliases__, _, parts}, body]}, ctx, src) do
    inner = %{ctx | module: ctx.module ++ Enum.map(parts, &Atom.to_string/1), fun: nil}
    block = kw(body, :do)
    Arms.def_clauses(block, inner, src) ++ walk(block, inner, src)
  end

  defp walk({def, _, [head | body]}, ctx, src) when def in @defs do
    {call, guard} = split_guard(head)
    {name, args} = name_args(call)
    inner = %{ctx | fun: "#{name}/#{length(args)}"}

    walk(args, %{inner | pattern: true}, src) ++
      walk(guard, %{inner | guard: true}, src) ++
      walk(body, inner, src)
  end

  defp walk({:@, _, [{attr, _, _}]}, _ctx, _src) when attr in @skip_attrs, do: []
  defp walk({form, _, _}, _ctx, _src) when form in @skip_forms, do: []

  defp walk({raise, _, args} = node, ctx, src) when raise in [:raise, :reraise] do
    here(node, ctx, src) ++ walk(args, %{ctx | no_literal: true}, src)
  end

  defp walk({:|>, _, [lhs, rhs]} = node, ctx, src) do
    here(node, ctx, src) ++ walk(lhs, ctx, src) ++ walk(rhs, %{ctx | pipe_rhs: true}, src)
  end

  defp walk({:->, _, [head, body]} = node, ctx, src) do
    here(node, ctx, src) ++ walk(head, %{ctx | pattern: true}, src) ++ walk(body, ctx, src)
  end

  defp walk({:when, _, [pattern, guard]}, ctx, src) do
    walk(pattern, ctx, src) ++ walk(guard, %{ctx | pattern: false, guard: true}, src)
  end

  defp walk({_, _, _} = node, ctx, src) do
    if excluded?(node, ctx.exclude) do
      []
    else
      here(node, ctx, src) ++ walk(children(node), %{ctx | pipe_rhs: false}, src)
    end
  end

  defp walk({a, b}, ctx, src), do: walk(a, ctx, src) ++ walk(b, ctx, src)
  defp walk(list, ctx, src) when is_list(list), do: Enum.flat_map(list, &walk(&1, ctx, src))
  defp walk(_leaf, _ctx, _src), do: []

  # cond's clause heads are conditions, not patterns.
  defp children({:cond, _, [kw]}),
    do: Enum.map(clauses(kw), fn {:->, m, [h, b]} -> {:cond_clause, m, [h, b]} end)

  defp children({{:., _, [left, _name]}, _, args}), do: [left | args]
  defp children({_, _, args}) when is_list(args), do: args
  defp children(_), do: []

  defp clauses(kw), do: List.wrap(kw(kw, :do))

  defp here(node, ctx, src) do
    Enum.flat_map(ctx.spells, fn spell ->
      Spells.candidates(spell, node, ctx, src) ++ Concurrency.candidates(spell, node, ctx, src)
    end)
  end

  # ---- helpers -----------------------------------------------------------

  @doc "Value of a keyword key in AST form, where keys may be wrapped by the literal encoder."
  def kw(list, key) when is_list(list) do
    Enum.find_value(list, fn
      {{:__block__, _, [^key]}, value} -> value
      {^key, value} -> value
      _ -> nil
    end)
  end

  def kw(_other, _key), do: nil

  defp split_guard({:when, _, [call, guard]}), do: {call, guard}
  defp split_guard(call), do: {call, nil}

  defp name_args({name, _, args}) when is_atom(name) and is_list(args), do: {name, args}
  defp name_args({name, _, _}) when is_atom(name), do: {name, []}
  defp name_args(_), do: {:unknown, []}

  defp excluded?(node, patterns) do
    case Spells.call_name(node) do
      nil -> false
      name -> Enum.any?(patterns, &matches?(name, &1))
    end
  end

  defp matches?(name, pattern) do
    case String.split(pattern, "*", parts: 2) do
      [exact] -> name == exact
      [prefix, ""] -> String.starts_with?(name, prefix)
      _ -> false
    end
  end
end
