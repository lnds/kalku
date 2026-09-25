defmodule Green do
  @moduledoc "A handful of functions with obvious holes, for measuring."

  def classify(n) when n >= 0, do: :non_negative
  def classify(_n), do: :negative

  def total(xs), do: Enum.reduce(xs, 0, &+/2)

  def shout(s), do: String.upcase(s) <> "!"

  # `limit` is what stops this. A wekufe that raises the limit makes the
  # test that calls it run forever, which is what `abort` is for.
  def count_to(limit), do: count_from(0, limit)

  defp count_from(n, limit) when n >= limit, do: n
  defp count_from(n, limit), do: count_from(n + 1, limit)
end
