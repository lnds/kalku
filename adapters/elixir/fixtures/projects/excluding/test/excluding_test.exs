defmodule ExcludingTest do
  use ExUnit.Case

  test "doubles" do
    assert Excluding.double(2) == 4
  end

  # `mix test` never runs this one.
  @tag :unsupported
  test "fails where it is not supported" do
    flunk("excluded by test_helper.exs")
  end
end
