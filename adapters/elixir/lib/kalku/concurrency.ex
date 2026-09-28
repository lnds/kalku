defmodule Kalku.Concurrency do
  @moduledoc """
  Candidates for the spells that change *when* something happens and
  *what happens when it dies*.

  Every other spell changes an expression and asks whether a test
  notices. These change a deadline or a supervision decision, which is
  where a BEAM project's hardest bugs live and where a suite most often
  has never looked.

  Two rules they inherit from the design, and neither is enforced here:
  they are opt-in, so a project asks for them by name; and their outcomes
  are believed only when repeated casts agree, which the kaikai side
  decides because only it knows a wekufe was cast twice.
  """

  alias Kalku.{Source, Spells, Walker}

  # A wait made instant is a deadline that fires every time; the slow
  # path becomes the only path, and a suite that never asserts what
  # happens then stays green.
  @awaits %{
    "Task.await" => :timeout_arg,
    "Task.yield" => :timeout_arg,
    "GenServer.call" => :timeout_arg,
    "Process.sleep" => :timeout_arg,
    "Task.await_many" => :timeout_arg
  }

  # `:one_for_one` restarts the child that died; `:one_for_all` takes its
  # siblings down with it. A suite that cannot tell them apart has never
  # killed a child and looked at what survived.
  @strategies %{
    one_for_one: ":one_for_all",
    one_for_all: ":one_for_one",
    rest_for_one: ":one_for_one"
  }

  # A child that is never brought back, and a death nobody hears about.
  @restarts %{permanent: ":temporary", transient: ":temporary"}

  @doc "Candidates the concurrency spells propose at one node."
  def candidates("await", node, ctx, src), do: await(node, ctx, src)
  def candidates("supervise", node, ctx, src), do: supervise(node, ctx, src)
  def candidates(_spell, _node, _ctx, _src), do: []

  # ---- await ---------------------------------------------------------------

  # The deadline of a `receive`: made zero, the wait expires immediately
  # and whatever the `after` clause does becomes the whole behaviour.
  defp await({:receive, _meta, [branches]}, ctx, src) do
    case Walker.kw(branches, :after) do
      [{:->, _, [[deadline], _body]} | _] -> zeroed("await", deadline, ctx, src)
      _ -> []
    end
  end

  defp await(node, ctx, src) do
    case Spells.call_name(node) do
      nil -> []
      name -> if Map.has_key?(@awaits, name), do: timeout_of(node, ctx, src), else: []
    end
  end

  # The timeout is the last argument of these calls, and only when it is
  # written down: `Task.await(t)` has a default this cannot reach without
  # rewriting the call, which would be two defects in one wekufe.
  defp timeout_of({_, _, args}, ctx, src) when is_list(args) do
    case List.last(args) do
      nil -> []
      last -> zeroed("await", last, ctx, src)
    end
  end

  defp zeroed(spell, {:__block__, meta, [n]} = node, ctx, src) when is_integer(n) and n > 0 do
    swap(spell, node, meta, "0", ctx, src)
  end

  defp zeroed(_spell, _node, _ctx, _src), do: []

  # ---- supervise -----------------------------------------------------------

  defp supervise({:__block__, meta, [atom]}, ctx, src) when is_atom(atom) do
    cond do
      Map.has_key?(@strategies, atom) -> named(meta, atom, @strategies[atom], ctx, src)
      Map.has_key?(@restarts, atom) -> named(meta, atom, @restarts[atom], ctx, src)
      true -> []
    end
  end

  defp supervise(node, ctx, src) do
    case Spells.call_name(node) do
      "Process.link" -> elide(node, ctx, src)
      "Process.monitor" -> elide(node, ctx, src)
      _ -> []
    end
  end

  # A link dropped is a death nobody hears about. The call is replaced by
  # its own argument, which keeps the expression well-typed wherever the
  # pid was being passed on.
  defp elide(node, ctx, src) do
    case Spells.candidates("call", node, ctx, src) do
      [candidate | _] -> [%{candidate | spell: "supervise"}]
      [] -> []
    end
  end

  # An atom is written as it is inspected, and the source is asked to
  # confirm it: a `:one_for_one` reached through an alias or a variable is
  # not a token this can replace.
  defp named(meta, atom, replacement, ctx, src) do
    text = inspect(atom)

    with p when p != nil <- Spells.pos(meta), true <- Source.at?(src, p, text) do
      [
        %{
          spell: "supervise",
          from: p,
          to: Source.advance(p, String.length(text)),
          replacement: replacement,
          enclosing: Walker.enclosing(ctx)
        }
      ]
    else
      _ -> []
    end
  end

  # ---- shared --------------------------------------------------------------

  defp swap(spell, node, meta, replacement, ctx, src) do
    with p when p != nil <- Spells.pos(meta),
         text when text != nil <- Spells.literal_text(node),
         true <- Source.at?(src, p, text) do
      [
        %{
          spell: spell,
          from: p,
          to: Source.advance(p, String.length(text)),
          replacement: replacement,
          enclosing: Walker.enclosing(ctx)
        }
      ]
    else
      _ -> []
    end
  end
end
