defmodule Instanced do
  @moduledoc "A function that adds, and a way to make the table's owner start again."

  def total(xs), do: Enum.reduce(xs, 0, &+/2)

  def start_owner_again do
    ref = Process.monitor(Instanced.Owner)
    Process.exit(Process.whereis(Instanced.Owner), :kill)
    receive do: ({:DOWN, ^ref, _, _, _} -> :ok)
    wait_for_owner()
  end

  defp wait_for_owner do
    if Process.whereis(Instanced.Owner), do: :ok, else: wait_for_owner()
  end
end
