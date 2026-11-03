defmodule Kalku.WorkersTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  setup :a_reni

  defp prepared(lines), do: reply(lines, "prepared")

  defp mark(reni, build, name) do
    case File.read(Path.join([reni, build, name])) do
      {:ok, text} -> text
      _ -> nil
    end
  end

  # stdout is the protocol: a step that prints, as `ecto.create` does,
  # must not put a line on it.
  defp all_json?(lines), do: Enum.all?(lines, &match?({:ok, _}, Kalku.Json.decode(&1)))

  test "a worker but the first takes a partition and makes its database, quietly", %{
    reni: reni
  } do
    lines = summon_as(reni, "partitioned", 2, "build-2", [request("prepare", 2)])

    assert prepared(lines)["modules"] > 0
    assert all_json?(lines)
    assert mark(reni, "build-2", "marked") == "2"
    assert mark(reni, "build-2", "noted") == "yes"
  end

  test "the first worker is prepared as before: no partition, no setting up", %{reni: reni} do
    lines = summon_as(reni, "partitioned", 0, "build", [request("prepare", 2)])

    assert prepared(lines)
    assert mark(reni, "build", "marked") == nil
  end

  test "a worker summoned with the first one's build refuses before compiling", %{reni: reni} do
    lines = summon_as(reni, "partitioned", 1, "build", [request("prepare", 2)])
    error = reply(lines, "error")

    assert error["code"] == "prepare_failed"
    assert error["message"] =~ "kalku init"
    refute File.exists?(Path.join([reni, "build", "lib", "partitioned"]))
  end

  test "the steps before `test` are the ones a worker runs" do
    noted = fn _ -> :ok end

    assert Kalku.Prepare.Database.steps(test: ["ecto.create --quiet", noted, "test", "after"]) ==
             ["ecto.create --quiet", noted]

    assert Kalku.Prepare.Database.steps(test: "test --cover") == []
    assert Kalku.Prepare.Database.steps([]) == []
  end
end
