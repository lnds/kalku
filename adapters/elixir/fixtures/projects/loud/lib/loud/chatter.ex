defmodule Loud.Chatter do
  @moduledoc "A process of the application that prints when it is asked to."
  use GenServer

  def start_link(_), do: GenServer.start_link(__MODULE__, nil, name: __MODULE__)
  def say(text), do: GenServer.call(__MODULE__, {:say, text})

  @impl true
  def init(nil), do: {:ok, nil}

  @impl true
  def handle_call({:say, text}, _from, state) do
    IO.puts(text)
    {:reply, :ok, state}
  end
end
