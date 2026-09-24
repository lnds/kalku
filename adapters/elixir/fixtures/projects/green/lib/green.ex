defmodule Green do
  @moduledoc "A handful of functions with obvious holes, for measuring."

  def classify(n) when n >= 0, do: :non_negative
  def classify(_n), do: :negative

  def total(xs), do: Enum.reduce(xs, 0, &+/2)

  def shout(s), do: String.upcase(s) <> "!"
end
