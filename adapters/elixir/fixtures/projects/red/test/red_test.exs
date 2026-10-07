defmodule RedTest do
  use ExUnit.Case

  # Says so each time it runs, for whoever counts how often the suite is run.
  test "this one passes" do
    IO.puts(:stderr, "red: the passing test ran")
    assert Red.classify(1) == :non_negative
  end

  test "this one does not" do
    assert Red.classify(-1) == :non_negative
  end
end
