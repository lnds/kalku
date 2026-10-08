defmodule StoppingTest do
  use ExUnit.Case

  test "nothing is stopped while there is work left" do
    assert Stopping.settle(10) == :ok
  end
end
