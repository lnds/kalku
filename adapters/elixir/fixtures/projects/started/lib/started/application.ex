defmodule Started.Application do
  use Application

  def start(_type, _args) do
    Supervisor.start_link([Started.Counter], strategy: :one_for_one, name: Started.Supervisor)
  end
end
