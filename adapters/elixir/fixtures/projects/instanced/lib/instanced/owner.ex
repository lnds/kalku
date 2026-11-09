defmodule Instanced.Owner do
  use GenServer

  def start_link(_), do: GenServer.start_link(__MODULE__, nil, name: __MODULE__)

  @impl true
  def init(nil) do
    :ets.new(:"instanced_#{inspect(self())}_db", [:named_table])
    {:ok, nil}
  end
end
