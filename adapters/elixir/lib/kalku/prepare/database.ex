defmodule Kalku.Prepare.Database do
  @moduledoc """
  Making a partitioned worker's test database, as `mix test` would.

  A project that partitions its tests names a database per partition, and
  `mix test` makes it through the project's `test` alias — typically
  `ecto.create` and `ecto.migrate` before `test` itself. The kalku runs the
  suite in its own runtime and never runs that alias, so a worker that took
  a partition of its own runs the steps that come before `test`.

  stdout carries the protocol, so whatever those steps write goes to
  stderr.
  """

  @doc """
  Runs the steps of the project's `test` alias that come before `test`,
  when this worker took a partition of its own.

  Returns `:ok`, or `{:error, "prepare_failed", message}` naming the step.
  """
  def set_up(false), do: :ok

  def set_up(true) do
    Kalku.Log.start_on_stderr()
    Kalku.Log.to_stderr()
    Enum.reduce_while(steps(), :ok, fn step, :ok -> run(step) end)
  end

  @doc "The steps of the `test` alias before `test` itself, in order."
  def steps(aliases \\ Mix.Project.config()[:aliases] || []) do
    aliases
    |> Keyword.get(:test, [])
    |> List.wrap()
    |> Enum.take_while(&(not test_itself?(&1)))
  end

  defp test_itself?(step) when is_binary(step), do: hd(OptionParser.split(step) ++ [""]) == "test"
  defp test_itself?(_), do: false

  defp run(step) do
    on_stderr(fn -> perform(step) end)
    {:cont, :ok}
  rescue
    e -> {:halt, failed(step, Exception.message(e))}
  catch
    :exit, reason -> {:halt, failed(step, inspect(reason))}
  end

  defp perform(step) when is_binary(step) do
    [task | args] = OptionParser.split(step)
    Mix.Task.rerun(task, args)
  end

  defp perform(step) when is_function(step, 1), do: step.([])

  defp failed(step, why) do
    {:error, "prepare_failed",
     "this worker's test database could not be made: the `test` alias step " <>
       "#{describe(step)} failed: #{why}"}
  end

  defp describe(step) when is_binary(step), do: "`#{step}`"
  defp describe(step), do: inspect(step)

  # What a step prints goes where the build's output goes. A process the
  # step starts takes the same group leader, and keeps it.
  defp on_stderr(fun) do
    leader = Process.group_leader()
    Process.group_leader(self(), Process.whereis(:standard_error))

    try do
      fun.()
    after
      Process.group_leader(self(), leader)
    end
  end
end
