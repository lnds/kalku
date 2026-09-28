defmodule ConcurrentTest do
  use ExUnit.Case

  # The suite this fixture is for: it exercises the happy path of every
  # concurrent decision and asserts nothing about what happens when a
  # deadline fires or a child dies. That is the hole the concurrency
  # spells exist to find, so it is planted on purpose.

  test "a waiter that answers is heard" do
    parent = self()
    pid = spawn(fn -> answer(parent) end)
    send(pid, {:start, self()})

    assert {:ok, :answered} = Concurrent.wait_for(pid)
  end

  test "a helper is started" do
    assert is_pid(Concurrent.helper_of(fn -> :ok end))
  end

  defp answer(_parent) do
    receive do
      {:ping, from} -> send(from, {:pong, :answered})
    end
  end
end
