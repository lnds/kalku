defmodule Started.Counter do
  use GenServer

  def start_link(_), do: GenServer.start_link(__MODULE__, 0, name: __MODULE__)

  def value, do: GenServer.call(__MODULE__, :value)
  def slack, do: GenServer.call(__MODULE__, :slack)

  def double(n), do: n * 2

  def sign(n), do: if(n > 0, do: :positive, else: :negative)

  @impl true
  def init(n), do: {:ok, n}

  @impl true
  def handle_call(:value, _from, n), do: {:reply, n, n}
  def handle_call(:slack, _from, n), do: {:reply, 7, n}
end
