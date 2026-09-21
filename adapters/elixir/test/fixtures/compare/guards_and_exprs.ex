defmodule Fx.Compare do
  def window(n) when n >= 0 and n < 10, do: n <= 5

  def eq(a, b) do
    a == b or a != b or a === b or a !== b or a > b
  end
end
