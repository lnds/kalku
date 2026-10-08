defmodule Red do
  @moduledoc "A project whose suite does not pass: one test fails, and one cannot be set up."

  def classify(n) when n >= 0, do: :non_negative
  def classify(_n), do: :negative
end
