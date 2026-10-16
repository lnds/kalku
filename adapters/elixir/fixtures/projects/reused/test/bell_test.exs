defmodule BellTest do
  use ExUnit.Case

  test "a bell rings twice" do
    assert Bell.rings(1) == 2
  end
end
