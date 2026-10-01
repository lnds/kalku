defmodule MockedTest do
  use ExUnit.Case, async: false
  use Mimic

  test "totals without touching the mock" do
    assert Mocked.total(1, 2) == 3
  end

  test "charges through a mocked rate" do
    Mocked.Rate |> expect(:of, fn _ -> 5 end)
    assert Mocked.charge(1) == :ok
  end
end
