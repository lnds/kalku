defmodule SameLineTest do
  use ExUnit.Case

  test "this test is written on the same line as one that covers lib/green.ex" do
    assert File.exists?("lib/green.ex")
  end
end
