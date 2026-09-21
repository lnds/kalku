defmodule Fx.Arm do
  def classify(x) do
    case x do
      0 -> :zero
      n when n > 0 -> :pos
      _ -> :neg
    end
  end

  def kind(x) do
    cond do
      is_integer(x) -> :int
      true -> :other
    end
  end

  def run(fun) do
    with {:ok, v} <- fun.() do
      v
    else
      {:error, e} -> e
      other -> other
    end
  end

  def fact(0), do: 1

  def fact(n) do
    n * fact(n - 1)
  end

  def single(x) do
    case x do
      _ -> x
    end
  end

  def same_line(x), do: (case x do 1 -> :a; _ -> :b end)
end
