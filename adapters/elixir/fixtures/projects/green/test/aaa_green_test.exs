defmodule AaaGreenTest do
  use ExUnit.Case

  # Sorted before the module that holds the kills, and green whatever is
  # spliced in: a kalku that read one module's total and called it the
  # suite would report every wekufe as a survivor, and this module is what
  # turns that into a failing test instead of a silent lie. It runs no
  # line of the code under measurement, so the coverage the baseline
  # reports is the same with it as without.
  test "the project under measurement is loaded" do
    assert Code.ensure_loaded?(Green)
  end
end
