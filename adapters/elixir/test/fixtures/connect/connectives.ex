defmodule Fx.Connect do
  def both(a, b), do: a and b
  def either(a, b), do: a or b
  def loose(a, b), do: a && (b || a)
end
