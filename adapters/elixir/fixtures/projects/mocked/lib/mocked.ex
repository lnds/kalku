defmodule Mocked do
  @moduledoc "Business logic that reaches the outside through `Rate`."

  def charge(amount) do
    if amount > 0 and Mocked.Rate.of(amount) > 0 do
      :ok
    else
      :error
    end
  end

  def total(a, b), do: a + b
end

defmodule Mocked.Rate do
  @moduledoc "The module the suite replaces."
  def of(amount), do: amount
end
