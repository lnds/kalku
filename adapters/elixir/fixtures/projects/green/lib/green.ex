defmodule Green do
  @moduledoc "A handful of functions with obvious holes, for measuring."

  def classify(n) when n >= 0, do: :non_negative
  def classify(_n), do: :negative

  def total(xs), do: Enum.reduce(xs, 0, &+/2)

  def shout(s), do: String.upcase(s) <> "!"

  # `limit` is what stops this. A wekufe that raises the limit makes the
  # test that calls it run forever, which is what `abort` is for.
  def count_to(limit), do: count_from(0, limit)

  # Writes application env, the way a project caches something it worked
  # out once. A wekufe here leaves state behind for the next cast to
  # trip over, which is what `dirty` and `reset` are about.
  def remember(value) do
    Application.put_env(:green, :remembered, value)
    Application.get_env(:green, :remembered)
  end

  defp count_from(n, limit) when n >= limit, do: n
  defp count_from(n, limit), do: count_from(n + 1, limit)
end
