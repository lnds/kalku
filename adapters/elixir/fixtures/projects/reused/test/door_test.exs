defmodule DoorTest do
  use ExUnit.Case

  test "knocking greets" do
    assert Door.knock("ana") == "hello, ana"
  end

  test "knocking greets anyone" do
    assert Door.knock("bo") == "hello, bo"
  end
end
