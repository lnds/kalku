defmodule ChimeTest do
  use ExUnit.Case

  test "a chime rings what the configuration names" do
    assert Chime.ring() == :ding
  end
end
