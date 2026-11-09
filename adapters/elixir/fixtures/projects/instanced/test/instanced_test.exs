defmodule InstancedTest do
  use ExUnit.Case

  test "total adds, after the table's owner started again" do
    assert Instanced.start_owner_again() == :ok
    assert Instanced.total([1, 2, 3]) == 6
  end
end
