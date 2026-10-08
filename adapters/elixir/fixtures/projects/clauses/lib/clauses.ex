defmodule Clauses do
  @moduledoc """
  The same boundary, drawn four ways.
  """

  def size(n) do
    cond do
      n > 10 -> :big
      true -> :small
    end
  end

  def sign(n) do
    case n do
      n when n > 0 -> :positive
      _ -> :other
    end
  end

  def withdraw(amount) when amount > 0 do
    {:ok, amount}
  end

  def pay(amount) when amount > 0, do: {:ok, amount}

  def idle, do: :idle
end
