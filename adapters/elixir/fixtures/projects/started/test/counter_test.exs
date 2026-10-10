defmodule Started.CounterTest do
  use ExUnit.Case

  test "the counter starts at zero" do
    assert Started.Counter.value() == 0
  end

  test "there is some slack" do
    assert is_integer(Started.Counter.slack())
  end

  test "twice as much" do
    assert Started.Counter.double(4) == 8
  end

  test "which side of zero" do
    assert Started.Counter.sign(5) == :positive
    assert Started.Counter.sign(-5) == :negative
  end
end
