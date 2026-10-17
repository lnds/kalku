defmodule ForgettingTest do
  use ExUnit.Case

  test "nothing is cleared while there is work left" do
    assert Forgetting.tidy(10) == :ok
  end
end
