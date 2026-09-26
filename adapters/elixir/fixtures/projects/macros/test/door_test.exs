defmodule DoorTest do
  use ExUnit.Case

  test "knocking greets" do
    assert Door.knock("ana") == "hello, ana"
  end
end
