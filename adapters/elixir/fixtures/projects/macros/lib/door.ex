defmodule Door do
  @moduledoc "Uses a macro, so it is a compile-time dependent of Greeter."
  require Greeter

  def knock(name), do: Greeter.greeting(name)

  def knocks, do: 3
end
