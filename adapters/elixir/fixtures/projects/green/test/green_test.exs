defmodule GreenTest do
  use ExUnit.Case

  test "classify names the sign" do
    assert Green.classify(1) == :non_negative
    assert Green.classify(-1) == :negative
  end

  test "total adds" do
    assert Green.total([1, 2, 3]) == 6
  end

  test "counting stops" do
    assert Green.count_to(10) == 10
  end
end
