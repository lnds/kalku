defmodule Greeter do
  @moduledoc "A macro module: anything that uses it recompiles when it changes."

  def politeness, do: 1

  defmacro greeting(name) do
    quote do
      "hello, " <> unquote(name)
    end
  end
end
