defmodule Door do
  @moduledoc "Uses a macro: its compiled code changes when the macro does."
  require Greeter

  def knock(name), do: Greeter.greeting(name)
end
