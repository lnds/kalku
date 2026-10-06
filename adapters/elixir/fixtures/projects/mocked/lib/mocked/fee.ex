defmodule Mocked.Fee do
  @moduledoc "The module a test replaces from its own `setup`."
  def of(amount), do: div(amount, 10)
end
