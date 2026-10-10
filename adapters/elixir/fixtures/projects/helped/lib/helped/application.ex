defmodule Helped.Application do
  use Application

  def start(_type, _args) do
    Supervisor.start_link([Helped.Flags], strategy: :one_for_one, name: Helped.Supervisor)
  end
end
