defmodule Helped.FlagsTest do
  use ExUnit.Case

  test "the helper made it ready" do
    assert Helped.Flags.get(:ready)
  end

  test "the limit it starts with" do
    assert Helped.Flags.get(:limit) == 10
  end
end
