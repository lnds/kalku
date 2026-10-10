defmodule Helped.Flags do
  use Agent

  def start_link(_), do: Agent.start_link(fn -> %{limit: 10} end, name: __MODULE__)

  def get(key), do: Agent.get(__MODULE__, &Map.get(&1, key))
  def put(key, value), do: Agent.update(__MODULE__, &Map.put(&1, key, value))
end
