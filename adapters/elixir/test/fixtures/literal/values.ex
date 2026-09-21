defmodule Fx.Literal do
  def base(0), do: 1
  def base(n), do: n * 42

  def status(true), do: :ok
  def status(false), do: :error

  def greeting, do: "hello"
  def empty, do: ""
  def escaped, do: "a\tb"
  def big, do: 1_000
end
