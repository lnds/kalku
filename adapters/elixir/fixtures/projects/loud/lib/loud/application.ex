defmodule Loud.Application do
  @moduledoc false
  use Application
  require Logger

  @impl true
  def start(_type, _args) do
    Logger.error("loud: could not reach what it wanted while starting")
    Supervisor.start_link([], strategy: :one_for_one)
  end
end
