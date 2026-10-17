defmodule Kalku.Runtime do
  @moduledoc """
  What a cast may have left behind, and putting it back.

  A wekufe is cast in a runtime that serves every other wekufe after it.
  State one cast leaves behind — a named ETS table, a changed application
  env — is read as the next cast's doing, and the next result is a lie.
  So each cast is weighed against a mark taken when the project was
  prepared, and a cast that moved anything says `dirty`.

  Only named ETS tables, the project's application env and ExUnit's are
  watched. Anonymous tables belong to whoever holds them and vanish with
  it, and processes come and go under a supervisor without anything being
  wrong; watching those would call a healthy runtime dirty on every cast.

  ExUnit is watched because it judges every cast after this one: a wekufe
  that changes its configuration, or a run that stopped halfway and left
  it holding the modules it was given, makes every later run fail before a
  test starts — `crashed`, cast after cast, for what none of them did.
  """

  @compile {:no_warn_undefined, :telemetry}

  @mark {__MODULE__, :mark}
  @outside {__MODULE__, :outside}
  @unended {__MODULE__, :unended}

  # What the kalku itself sets before every run: no measure of what a cast
  # changed.
  @per_run [:include, :exclude, :max_cases, :max_failures]

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

  @doc """
  Record the handlers telemetry and the logger hold before the project's
  own application starts.

  What the project registers there as it starts outlives its application:
  stopping it leaves them in place, and starting it again finds them
  already registered. Taken after its dependencies have started, because
  what they registered is theirs, and they are not started again.
  """
  def mark_outside do
    :persistent_term.put(@outside, handlers())
    :ok
  end

  @doc """
  Says that a run of the tests stopped before it ended.

  ExUnit is then left with that run still open and refuses the next one;
  only starting it again puts that right.
  """
  def unended, do: :persistent_term.put(@unended, true)

  @doc "True when the runtime no longer matches the mark."
  def dirty?(app) do
    case :persistent_term.get(@mark, nil) do
      nil -> false
      clean -> unended?() or snapshot(app) != clean
    end
  end

  defp unended?, do: :persistent_term.get(@unended, false)

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
        restore_env(clean.runner, :ex_unit, @per_run)
        runner = start_runner_again()
        restarted = restart(app)
        {:ok, runner == :ok and restarted == :ok and snapshot(app) == clean}
    end
  end

  @doc "What is being watched, as it stands."
  def snapshot(app) do
    %{
      tables: named_tables(),
      env: Enum.sort(Application.get_all_env(app)),
      runner: Enum.sort(Keyword.drop(Application.get_all_env(:ex_unit), @per_run))
    }
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
  defp restore_env(clean, app, kept \\ []) do
    for {key, _value} <- Application.get_all_env(app),
        key not in kept,
        not List.keymember?(clean, key, 0) do
      safely(fn -> Application.delete_env(app, key) end)
    end

    for {key, value} <- clean do
      safely(fn -> Application.put_env(app, key, value) end)
    end
  end

  # Its configuration lives in the application env, which stopping the
  # application leaves in place.
  defp start_runner_again do
    if unended?() do
      safely(fn -> Application.stop(:ex_unit) end)
      :persistent_term.erase(@unended)

      case safely(fn -> Application.ensure_all_started(:ex_unit) end) do
        {:ok, _} -> :ok
        _ -> :error
      end
    else
      :ok
    end
  end

  @doc """
  Stops the application and starts it again. `:ok`, or why it did not
  start.
  """

  def restart(app) do
    safely(fn -> Application.stop(app) end)
    forget_registered()
    started = safely(fn -> Application.ensure_all_started(app) end)
    Kalku.Log.to_stderr()

    case started do
      {:ok, _} -> :ok
      {:error, reason} -> {:error, Kalku.Baseline.first_lines(inspect(reason))}
      _ -> {:error, "it could not be started"}
    end
  end

  # Whatever was registered since `mark_outside`: the project's own start
  # registers it again.
  defp forget_registered do
    case :persistent_term.get(@outside, nil) do
      nil ->
        :ok

      before ->
        for id <- telemetry_ids(),
            id not in before.telemetry,
            do: safely(fn -> :telemetry.detach(id) end)

        for id <- :logger.get_handler_ids(),
            id not in before.logger,
            do: safely(fn -> :logger.remove_handler(id) end)

        :ok
    end
  end

  defp handlers, do: %{telemetry: telemetry_ids(), logger: :logger.get_handler_ids()}

  # Telemetry is a dependency of most projects and of none of the kalku's.
  defp telemetry_ids do
    if Code.ensure_loaded?(:telemetry) and function_exported?(:telemetry, :list_handlers, 1) do
      for %{id: id} <- :telemetry.list_handlers([]), do: id
    else
      []
    end
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
