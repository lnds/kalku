defmodule Kalku.BaselineTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  describe "against a real project" do
    setup :a_reni

    test "a green suite is reported green, with every test named and timed", %{reni: reni} do
      done = reply(run(reni, "green", ["baseline"]), "baseline_done")

      assert done["status"] == "green"
      assert done["failures"] == []
      assert done["duration_ms"] >= 0

      # A test is named by where it is written, relative to the project:
      # every kalku in a pool has to call the same test the same thing.
      assert Enum.map(done["tests"], & &1["test"]) == [
               "test/green_test.exs:4",
               "test/green_test.exs:9"
             ]

      assert Enum.all?(done["tests"], &(&1["file"] == "test/green_test.exs"))
      assert Enum.all?(done["tests"], &(&1["duration_ms"] >= 0))
    end

    # A suite that is already failing cannot say whether a wekufe was
    # noticed, so the baseline says so plainly rather than scoring it.
    test "a suite with a failing test is red, and the failure is quoted", %{reni: reni} do
      done = reply(run(reni, "red", ["baseline"]), "baseline_done")

      assert done["status"] == "red"
      assert [%{"test" => "test/red_test.exs:8", "message" => message}] = done["failures"]

      assert message =~ "Assertion with == failed"
      assert message =~ "left:  :negative"
      refute message =~ "stacktrace"

      # The test that passed is still reported: red is about the suite,
      # not about every test in it.
      assert length(done["tests"]) == 2
    end

    test "the suite runs in the kalku's own runtime, so it can be asked twice", %{reni: reni} do
      lines = run(reni, "green", ["baseline", "baseline"])

      [first, second] =
        lines
        |> Enum.map(&JSON.decode!/1)
        |> Enum.filter(&(&1["type"] == "baseline_done"))

      assert first["status"] == "green"
      assert second["status"] == "green"
      assert length(second["tests"]) == length(first["tests"])
    end

    test "baseline before prepare is refused, and not fatally", %{reni: reni} do
      lines = summon(reni, "green", [~s({"type":"baseline","id":2})])
      error = reply(lines, "error")

      assert error["code"] == "not_prepared"
      assert error["fatal"] == false
    end
  end
end
