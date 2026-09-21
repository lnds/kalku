defmodule Fx.ArmMore do
  def await do
    receive do
      {:ok, v} -> v
      :stop -> nil
    after
      100 -> :timeout
    end
  end

  def pick do
    fn
      0 -> :zero
      _ -> :other
    end
  end

  def one do
    fn x -> x end
  end
end
