defmodule Red do
  @moduledoc "A project whose suite does not pass, which kalku must refuse to measure."

  def classify(n) when n >= 0, do: :non_negative
  def classify(_n), do: :negative
end
