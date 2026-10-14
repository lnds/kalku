defmodule AttachedTest do
  use ExUnit.Case

  test "total adds" do
    assert Attached.total([1, 2, 3]) == 6
  end

  test "remembering keeps what it was given" do
    assert Attached.remember(:once) == :once
  end

  test "the application is up" do
    assert Process.whereis(Attached.Supervisor)
  end
end
