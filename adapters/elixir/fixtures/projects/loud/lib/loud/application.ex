defmodule Loud.Application do
  @moduledoc false
  use Application
  require Logger

  @impl true
  def start(_type, _args) do
    Logger.error("loud: could not reach what it wanted while starting")
    IO.puts("loud: starting")
    Supervisor.start_link([Loud.Chatter], strategy: :one_for_one)
  end
end
