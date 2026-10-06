defmodule LateCopyTest do
  use ExUnit.Case, async: false
  use Mimic

  # Copied when a test runs, not when the suite loads.
  setup do
    Mimic.copy(Mocked.Fee)
    :ok
  end

  test "takes a mocked fee" do
    Mocked.Fee |> expect(:of, fn _ -> 2 end)
    assert Mocked.Fee.of(100) == 2
  end
end
