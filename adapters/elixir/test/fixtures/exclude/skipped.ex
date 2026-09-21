defmodule Fx.Exclude do
  @moduledoc "Docs are not code: 1 > 0 and true."
  @doc "Neither is this: 0"
  @spec go(integer()) :: integer()
  def go(n) do
    Logger.debug("n is #{n}", count: 1)
    if n > 0, do: raise("bad input: 7"), else: n
  end

  def same(x) do
    if x do
      :same
    else
      :same
    end
  end
end
