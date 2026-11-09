defmodule Instanced.Application do
  use Application

  def start(_type, _args) do
    Supervisor.start_link([Instanced.Owner], strategy: :one_for_one, name: Instanced.Supervisor)
  end
end
