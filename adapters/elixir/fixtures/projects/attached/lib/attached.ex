defmodule Attached do
  @moduledoc "Two functions: one that adds, one that leaves state behind."

  def total(xs), do: Enum.reduce(xs, 0, &+/2)

  def remember(value) do
    Application.put_env(:attached, :remembered, value)
    Application.get_env(:attached, :remembered)
  end
end
