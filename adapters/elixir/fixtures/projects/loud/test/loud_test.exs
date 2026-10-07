defmodule LoudTest do
  use ExUnit.Case

  test "doubles" do
    assert Loud.double(2) == 4
  end
end
