defmodule MockedEarlyTest do
  use ExUnit.Case, async: false
  use Mimic

  test "charges through the real rate" do
    assert MockedEarly.charge(3) == :ok
  end

  test "charges through a mocked rate" do
    MockedEarly.Rate |> expect(:of, fn _ -> 0 end)
    assert MockedEarly.charge(3) == :error
  end
end
