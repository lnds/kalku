defmodule ErlangTest do
  use ExUnit.Case

  test "pairs" do
    assert Erlang.pairs([1, 2, 3, 4]) == 2
  end
end
