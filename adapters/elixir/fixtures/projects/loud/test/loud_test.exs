defmodule LoudTest do
  use ExUnit.Case

  test "doubles, and says so" do
    IO.puts("loud: a test printing")
    Loud.Chatter.say("loud: the application printing")
    assert Loud.double(2) == 4
  end
end
