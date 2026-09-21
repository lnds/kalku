defmodule Fx.Call do
  def normalize(s) do
    s
    |> String.trim()
    |> String.downcase()
  end

  def wrap(x), do: List.wrap(x)
  def local(x), do: helper(x, 1)
  def nested(x), do: Enum.map(x, &helper(&1, 2))
  def noargs, do: make_ref()

  defp helper(x, _n), do: x
end
