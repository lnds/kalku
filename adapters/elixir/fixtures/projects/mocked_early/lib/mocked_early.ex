defmodule MockedEarly do
  @moduledoc "Business logic that reaches the outside through `Rate`."

  def charge(amount) do
    if amount > 0 and MockedEarly.Rate.of(amount) > 0 do
      :ok
    else
      :error
    end
  end
end

defmodule MockedEarly.Rate do
  @moduledoc "The module the suite replaces."
  def of(amount) do
    doubled = amount * 2
    doubled - amount
  end
end
