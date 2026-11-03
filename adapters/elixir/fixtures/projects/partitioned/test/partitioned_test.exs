defmodule PartitionedTest do
  use ExUnit.Case

  test "doubles" do
    assert Partitioned.double(2) == 4
  end
end
