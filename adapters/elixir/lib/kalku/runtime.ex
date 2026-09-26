defmodule Kalku.Runtime do
  @moduledoc """
  What a cast may have left behind, and putting it back.

  A wekufe is cast in a runtime that serves every other wekufe after it.
  State one cast leaves behind — a named ETS table, a changed application
  env — is read as the next cast's doing, and the next result is a lie.
  So each cast is weighed against a mark taken when the project was
  prepared, and a cast that moved anything says `dirty`.

  Only named ETS tables and the project's application env are watched.
  Anonymous tables belong to whoever holds them and vanish with it, and
  processes come and go under a supervisor without anything being wrong;
  watching those would call a healthy runtime dirty on every cast.
  """

  @mark {__MODULE__, :mark}

  @doc """
  Record what a clean runtime looks like.

  Taken again after the baseline, and that is the one that counts: the
  suite itself creates named tables the first time it runs, and a mark
  from before them would have `reset` delete the test framework's own
  state and leave the kalku unable to run a test at all.
  """
  def mark(app) do
    :persistent_term.put(@mark, snapshot(app))
    :ok
  end

  @doc "True when the runtime no longer matches the mark."
  def dirty?(app) do
    case :persistent_term.get(@mark, nil) do
      nil -> false
      clean -> snapshot(app) != clean
    end
  end

  @doc """
  Put the runtime back the way it was marked.

  Tables the project created are deleted, the application env is restored
  entry by entry, and the application is restarted so anything holding
  the old state is holding it no longer. Returns whether the runtime
  matches the mark afterwards — a reset that did not work says so, and
  the kaikai side recycles the kalku rather than trusting it.
  """
  def reset(app) do
    case :persistent_term.get(@mark, nil) do
      nil ->
        {:error, "this kalku has nothing to reset to: the project was never prepared"}

      clean ->
        drop_new_tables(clean.tables, app)
        restore_env(clean.env, app)
        restart(app)
        {:ok, snapshot(app) == clean}
    end
  end

  @doc "What is being watched, as it stands."
  def snapshot(app) do
    %{tables: named_tables(), env: Enum.sort(Application.get_all_env(app))}
  end

  # A named table is a deliberate one: something asked for that name and
  # expects to find it again.
  defp named_tables, do: for(table <- :ets.all(), is_atom(table), into: MapSet.new(), do: table)

  defp drop_new_tables(clean, _app) do
    for table <- :ets.all(), is_atom(table), not MapSet.member?(clean, table) do
      safely(fn -> :ets.delete(table) end)
    end
  end

  # Both directions: what the cast changed goes back, and what it added is
  # taken away. Restoring only the keys we know would leave the additions.
  defp restore_env(clean, app) do
    for {key, _value} <- Application.get_all_env(app), not List.keymember?(clean, key, 0) do
      safely(fn -> Application.delete_env(app, key) end)
    end

    for {key, value} <- clean do
      safely(fn -> Application.put_env(app, key, value) end)
    end
  end

  defp restart(app) do
    safely(fn -> Application.stop(app) end)
    safely(fn -> Application.ensure_all_started(app) end)
    Kalku.Log.to_stderr()
  end

  # A table another process owns cannot be deleted from here, and an
  # application that will not stop is the caller's problem to report, not
  # a reason to take the kalku down.
  defp safely(action) do
    action.()
  rescue
    _ -> :error
  catch
    _, _ -> :error
  end
end
