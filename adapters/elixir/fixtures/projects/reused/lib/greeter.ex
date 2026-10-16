defmodule Greeter do
  @moduledoc "A macro, expanded into the module that uses it."

  defmacro greeting(name) do
    quote do
      "hello, " <> unquote(name)
    end
  end
end
