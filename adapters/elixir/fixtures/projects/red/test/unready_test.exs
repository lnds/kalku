defmodule UnreadyTest do
  use ExUnit.Case

  setup_all do
    raise "nothing here can be set up"
  end

  # Never runs, and is not a test that passed.
  test "this one is never reached" do
    assert Red.classify(1) == :non_negative
  end
end
