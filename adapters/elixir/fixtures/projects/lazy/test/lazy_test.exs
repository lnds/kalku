defmodule LazyTest do
  use ExUnit.Case

  test "a price is the amount at the rate" do
    assert Lazy.price(10) == 20
  end

  test "the rate is two" do
    assert Lazy.rate() == 2
  end
end
