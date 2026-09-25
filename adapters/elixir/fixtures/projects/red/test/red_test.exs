defmodule RedTest do
  use ExUnit.Case

  test "this one passes" do
    assert Red.classify(1) == :non_negative
  end

  test "this one does not" do
    assert Red.classify(-1) == :non_negative
  end
end
