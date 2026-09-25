defmodule Kalku.LoopTest do
  use ExUnit.Case, async: true

  alias Kalku.{Loop, Protocol}

  @root Path.expand("fixtures", __DIR__)

  defp hello(protocol \\ 1),
    do:
      ~s({"type":"hello","id":1,"protocol":#{protocol},"root":"#{@root}","reni":"/tmp/reni","worker":0,"inline_limit_bytes":1048576,"env":{}})

  defp reply!(line) do
    {:ok, msg} = Protocol.decode(line, :reply)
    msg
  end

  defp ready_state do
    {:reply, _, state} = Loop.handle(hello(), %Loop{})
    state
  end

  test "hello is answered with ready, as a native kalku that is honest about its capabilities" do
    {:reply, line, _state} = Loop.handle(hello(), %Loop{})
    msg = reply!(line)
    assert msg.type == "ready"
    # A capability is claimed only once it works: `cast` because this is a
    # native kalku, `per_test_coverage` because `baseline` measures it,
    # `code_hash` and `hot_load` because a cast reports one and does the
    # other.
    assert msg.body["capabilities"] ==
             ["cast", "per_test_coverage", "code_hash", "hot_load", "abort"]

    assert msg.body["spells"] == ~w(arm compare connect negate literal call)
  end

  test "a protocol version mismatch is a fatal error" do
    {:reply, line, _} = Loop.handle(hello(2), %Loop{})

    assert %{type: "error", body: %{"code" => "protocol_mismatch", "fatal" => true}} =
             reply!(line)
  end

  test "sites_found lists sites and skips test files, scripts, and unreadable files" do
    req =
      ~s({"type":"sites","id":2,"files":["connect/connectives.ex","test/a_test.exs","x.exs","nope.ex"],"spells":["connect"],"exclude_calls":[]})

    {:reply, line, _} = Loop.handle(req, ready_state())
    %{type: "sites_found", body: body} = reply!(line)

    assert length(body["sites"]) == 4
    assert Enum.map(body["skipped"], & &1["reason"]) == ["not_source", "not_source", "unreadable"]
  end

  test "a file that does not parse is skipped with parse_error" do
    dir = Path.join(System.tmp_dir!(), "kalku-loop-#{System.unique_integer([:positive])}")
    File.mkdir_p!(Path.join(dir, "lib"))
    File.write!(Path.join(dir, "lib/bad.ex"), "defmodule X do\n  def f(, do: 1\nend\n")

    {:reply, _, state} = Loop.handle(String.replace(hello(), @root, dir), %Loop{})
    req = ~s({"type":"sites","id":2,"files":["lib/bad.ex"],"spells":["arm"],"exclude_calls":[]})
    {:reply, line, _} = Loop.handle(req, state)
    assert [%{"reason" => "parse_error"}] = reply!(line).body["skipped"]
  end

  test "requests this kalku does not serve yet get a non-fatal bad_request" do
    {:reply, line, _} = Loop.handle(~s({"type":"cast","id":3}), ready_state())

    assert %{type: "error", id: 3, body: %{"code" => "bad_request", "fatal" => false}} =
             reply!(line)
  end

  # Compiling into a user's own `_build` would leave their tree changed, so
  # a kalku that was not given a reni refuses before it compiles anything.
  test "prepare without an isolated reni is a fatal error, not a compile" do
    {:reply, line, _} = Loop.handle(~s({"type":"prepare","id":4}), ready_state())

    assert %{type: "error", id: 4, body: %{"code" => "reni_not_isolated", "fatal" => true}} =
             reply!(line)
  end

  test "a malformed line gets bad_request naming the protocol error kind" do
    {:reply, line, _} = Loop.handle("garbage", %Loop{})
    assert %{type: "error", id: 0, body: %{"message" => "not_json: " <> _}} = reply!(line)
  end

  test "shutdown is answered with bye and stops the loop" do
    assert {:stop, line} = Loop.handle(~s({"type":"shutdown","id":7}), ready_state())
    assert %{type: "bye", id: 7} = reply!(line)
  end
end
