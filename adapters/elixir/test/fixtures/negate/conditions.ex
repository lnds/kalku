defmodule Fx.Negate do
  def sign(n) do
    if n > 0 do
      :pos
    else
      :neg
    end
  end

  def skip(x), do: unless(x, do: :run, else: :skip)

  def flip(x), do: not x
  def bang(x), do: !x
end
