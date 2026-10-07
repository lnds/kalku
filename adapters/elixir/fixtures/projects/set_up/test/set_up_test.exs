defmodule SetUpTest do
  use ExUnit.Case

  # Refuses to go on with too few seats: none of the tests below runs then.
  setup_all do
    seats = SetUp.seats()
    true = seats >= 10
    {:ok, seats: seats}
  end

  test "there is a seat for everyone", %{seats: seats} do
    assert seats > 0
  end
end
