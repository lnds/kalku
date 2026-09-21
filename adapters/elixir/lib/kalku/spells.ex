defmodule Kalku.Spells do
  @moduledoc """
  Per-node candidates for every spell except `arm` (see `Kalku.Arms`).

  The AST says what and where; the source only confirms that the expected
  token sits at the reported position. A node whose text cannot be pinned
  down exactly yields no candidate rather than a guess.
  """

  alias Kalku.{Arms, Source, Walker}

  @compare %{
    :>= => ">",
    :> => ">=",
    :<= => "<",
    :< => "<=",
    :== => "!=",
    :!= => "==",
    :=== => "!==",
    :!== => "==="
  }
  @connect %{and: "or", or: "and", &&: "||", ||: "&&"}

  # Forms that look like calls but are definitions, control flow, or macros
  # whose argument is not a value to pass through.
  @not_calls ~w(def defp defmacro defmacrop defmodule defstruct defimpl defprotocol defdelegate
                defguard defguardp defexception defoverridable alias import require use if unless
                case cond with fn for receive try quote unquote unquote_splicing raise reraise
                throw super binding __block__ __aliases__ __MODULE__ __DIR__ __ENV__ __CALLER__)a

  @type candidate :: %{
          spell: String.t(),
          from: Source.pos(),
          to: Source.pos(),
          replacement: String.t(),
          enclosing: String.t() | nil
        }

  @doc "Candidates one spell proposes at one node."
  def candidates("compare", {op, meta, [_, _]}, ctx, src) when is_map_key(@compare, op),
    do: token_swap("compare", Atom.to_string(op), @compare[op], meta, ctx, src)

  def candidates("connect", {op, meta, [_, _]}, ctx, src) when is_map_key(@connect, op),
    do: token_swap("connect", Atom.to_string(op), @connect[op], meta, ctx, src)

  def candidates("negate", node, ctx, src), do: negate(node, ctx, src)
  def candidates("literal", node, ctx, src), do: literal(node, ctx, src)
  def candidates("call", node, ctx, src), do: call(node, ctx, src)
  def candidates("arm", node, ctx, src), do: Arms.clauses(node, ctx, src)
  def candidates(_spell, _node, _ctx, _src), do: []

  # ---- compare / connect --------------------------------------------------

  defp token_swap(spell, text, replacement, meta, ctx, src) do
    case pos(meta) do
      nil -> []
      p -> if Source.at?(src, p, text), do: [cand(spell, p, text, replacement, ctx)], else: []
    end
  end

  # ---- negate --------------------------------------------------------------

  defp negate({kw, meta, [_cond, branches]}, ctx, src) when kw in [:if, :unless] do
    other = if kw == :if, do: "unless", else: "if"

    if identical_branches?(branches),
      do: [],
      else: token_swap("negate", Atom.to_string(kw), other, meta, ctx, src)
  end

  defp negate({op, meta, [_operand]}, ctx, src) when op in [:not, :!] do
    text = Atom.to_string(op)

    with p when p != nil <- pos(meta), true <- Source.at?(src, p, text) do
      spaces =
        src
        |> Source.line(elem(p, 0))
        |> String.slice((elem(p, 1) - 1 + String.length(text))..-1//1)

      width =
        String.length(text) + String.length(spaces) - String.length(String.trim_leading(spaces))

      [
        %{
          spell: "negate",
          from: p,
          to: Source.advance(p, width),
          replacement: "",
          enclosing: Walker.enclosing(ctx)
        }
      ]
    else
      _ -> []
    end
  end

  defp negate(_node, _ctx, _src), do: []

  defp identical_branches?(branches) do
    strip(Walker.kw(branches, :do)) == strip(Walker.kw(branches, :else))
  end

  defp strip(ast), do: Macro.prewalk(ast, &Macro.update_meta(&1, fn _ -> [] end))

  # ---- literal -------------------------------------------------------------

  defp literal(_node, %{no_literal: true}, _src), do: []

  defp literal({:__block__, meta, [value]} = node, ctx, src) do
    with p when p != nil <- pos(meta),
         text when text != nil <- literal_text(node),
         true <- Source.at?(src, p, text),
         repl when repl != nil <- perturb(value) do
      [cand("literal", p, text, repl, ctx)]
    else
      _ -> []
    end
  end

  defp literal(_node, _ctx, _src), do: []

  defp perturb(n) when is_integer(n), do: Integer.to_string(n + 1)
  defp perturb(true), do: "false"
  defp perturb(false), do: "true"
  defp perturb(:ok), do: ":error"
  defp perturb(:error), do: ":ok"
  defp perturb(s) when is_binary(s) and s != "", do: ~s("")
  defp perturb(_), do: nil

  @doc "Exact source text of a literal node, when it can be known without reading the source."
  def literal_text({:__block__, meta, [n]}) when is_integer(n), do: meta[:token]
  def literal_text({:__block__, _, [b]}) when is_boolean(b), do: Atom.to_string(b)
  def literal_text({:__block__, _, [a]}) when a in [:ok, :error], do: inspect(a)

  def literal_text({:__block__, meta, [s]}) when is_binary(s),
    do: if(meta[:delimiter] == ~s("), do: inspect(s))

  def literal_text(_), do: nil

  # ---- call ----------------------------------------------------------------

  defp call(_node, %{pattern: true}, _src), do: []
  defp call(_node, %{guard: true}, _src), do: []

  defp call({:|>, meta, [_lhs, rhs]}, ctx, src) do
    with p when p != nil <- pos(meta),
         true <- Source.at?(src, p, "|>"),
         {:ok, close} <- closing(rhs) do
      [%{spell: "call", from: p, to: close, replacement: "", enclosing: Walker.enclosing(ctx)}]
    else
      _ -> []
    end
  end

  defp call(_node, %{pipe_rhs: true}, _src), do: []

  defp call({_, _, [first | _]} = node, ctx, src) do
    with name when name != nil <- call_name(node),
         {:ok, start} <- call_start(node),
         {:ok, close} <- closing(node),
         {:ok, text} <- arg_text(first, src) do
      [
        %{
          spell: "call",
          from: start,
          to: close,
          replacement: text,
          enclosing: Walker.enclosing(ctx)
        }
      ]
    else
      _ -> []
    end
  end

  defp call(_node, _ctx, _src), do: []

  @doc "Dotted name of a call node (`\"Logger.debug\"`, `\":telemetry.execute\"`, `\"f\"`), or `nil`."
  def call_name({{:., _, [{:__aliases__, _, parts}, fun]}, _, args})
      when is_atom(fun) and is_list(args),
      do: Enum.join(parts, ".") <> "." <> Atom.to_string(fun)

  def call_name({{:., _, [{:__block__, _, [mod]}, fun]}, _, args})
      when is_atom(mod) and is_atom(fun) and is_list(args),
      do: inspect(mod) <> "." <> Atom.to_string(fun)

  def call_name({name, meta, args})
      when is_atom(name) and is_list(args) and name not in @not_calls do
    if Keyword.has_key?(meta, :closing), do: Atom.to_string(name)
  end

  def call_name(_), do: nil

  defp call_start({{:., _, [left, _]}, _, _}), do: ok(pos(elem(left, 1)))
  defp call_start({_, meta, _}), do: ok(pos(meta))

  defp closing({_, meta, args}) when is_list(args) do
    case meta[:closing] do
      [line: l, column: c] -> {:ok, {l, c + 1}}
      _ -> :none
    end
  end

  defp closing(_), do: :none

  defp arg_text({name, meta, ctx}, src) when is_atom(name) and is_atom(ctx),
    do: text_at(src, meta, Atom.to_string(name))

  defp arg_text({:__block__, meta, _} = lit, src), do: text_at(src, meta, literal_text(lit))

  defp arg_text(_, _), do: :none

  defp text_at(_src, _meta, nil), do: :none

  defp text_at(src, meta, text) do
    case pos(meta) do
      nil -> :none
      p -> if Source.at?(src, p, text), do: {:ok, text}, else: :none
    end
  end

  # ---- shared ----------------------------------------------------------------

  @doc "The `{line, col}` of a node's metadata, or `nil`."
  def pos(meta) when is_list(meta) do
    with l when is_integer(l) <- meta[:line],
         c when is_integer(c) <- meta[:column],
         do: {l, c},
         else: (_ -> nil)
  end

  def pos(_), do: nil

  defp ok(nil), do: :none
  defp ok(p), do: {:ok, p}

  defp cand(spell, p, text, replacement, ctx) do
    %{
      spell: spell,
      from: p,
      to: Source.advance(p, String.length(text)),
      replacement: replacement,
      enclosing: Walker.enclosing(ctx)
    }
  end
end
