defmodule Lazy do
  @key {__MODULE__, :rate}

  def rate do
    case :persistent_term.get(@key, nil) do
      nil -> learn()
      rate -> rate
    end
  end

  def price(amount), do: amount * rate()

  defp learn do
    :persistent_term.put(@key, 2)
    2
  end
end
