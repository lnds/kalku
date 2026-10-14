defmodule Attached.Application do
  use Application

  def start(_type, _args) do
    Logger.add_handlers(:attached)
    :ok = :telemetry.attach("attached-events", [:attached, :event], &__MODULE__.handle/4, nil)
    :ok = :logger.add_handler(:attached_audit, :logger_std_h, %{level: :none})
    Supervisor.start_link([], strategy: :one_for_one, name: Attached.Supervisor)
  end

  def handle(_event, _measurements, _metadata, _config), do: :ok
end
