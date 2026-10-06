defmodule TidiedLateTest do
  use ExUnit.Case, async: false

  # What a library does when it is set up from a test rather than from
  # `test_helper.exs`: it asks to tidy up when the suite ends.
  setup do
    ExUnit.after_suite(fn _ -> raise "tidied up between two runs of the suite" end)
    :ok
  end

  test "asks to tidy up after the suite" do
    assert 2 + 2 == 4
  end
end
