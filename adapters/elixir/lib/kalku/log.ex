defmodule Kalku.Log do
  @moduledoc """
  Keeping every log off stdout.

  stdout carries the protocol. A log line on it is a line the kaikai side
  reads as a kalku writing nonsense, and it banishes the worker for
  writing something the project under test wrote.

  A handler's `type` cannot be changed once it is installed — OTP answers
  `{:error, {:illegal_config_change, ...}}` — so the handler is removed
  and added again pointing at stderr, keeping the formatter it had.

  Doing it once is not enough either: starting the project's applications
  installs the handlers again, so this runs after anything that starts or
  stops one.
  """

  @doc "Point the default handler at stderr, however many times it takes."
  def to_stderr do
    case :logger.get_handler_config(:default) do
      {:ok, %{config: %{type: :standard_error}}} -> :ok
      {:ok, config} -> reinstall(config)
      _ -> :ok
    end
  rescue
    _ -> :ok
  catch
    _, _ -> :ok
  end

  defp reinstall(%{module: module} = config) do
    :logger.remove_handler(:default)

    :logger.add_handler(
      :default,
      module,
      config
      |> Map.take([:level, :filters, :filter_default, :formatter])
      |> Map.put(:config, %{type: :standard_error})
    )

    :ok
  end

  defp reinstall(_config), do: :ok
end
