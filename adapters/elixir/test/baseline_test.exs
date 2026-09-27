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
      assert Enum.sort(Enum.map(done["tests"], & &1["test"])) == [
               "test/aaa_green_test.exs:8",
               "test/green_test.exs:13",
               "test/green_test.exs:17",
               "test/green_test.exs:4",
               "test/green_test.exs:9"
             ]

      assert Enum.all?(done["tests"], &(&1["file"] =~ "_test.exs"))
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

    # What the coverage is for: a wekufe on `classify` is cast against the
    # test that runs `classify`, not against the whole suite.
    test "each line is attributed to the tests that actually run it", %{reni: reni} do
      done = reply(run(reni, "green", ["baseline"]), "baseline_done")

      by_line = Map.new(done["coverage"], &{&1["line"], &1["tests"]})

      assert by_line[4] == ["test/green_test.exs:4"]
      assert by_line[5] == ["test/green_test.exs:4"]
      assert by_line[7] == ["test/green_test.exs:9"]

      assert Enum.all?(done["coverage"], &(&1["file"] == "lib/green.ex"))
    end

    # A line nobody runs has no entry: the question is which tests cover a
    # line, and for an uncovered line the honest answer is none.
    test "a line no test runs is absent rather than empty", %{reni: reni} do
      done = reply(run(reni, "green", ["baseline"]), "baseline_done")
      lines = Enum.map(done["coverage"], & &1["line"])

      refute 9 in lines
      refute Enum.any?(done["coverage"], &(&1["tests"] == []))
    end

    test "the kalku says it can do this only now that it can", %{reni: reni} do
      ready = reply(summon(reni, "green", []), "ready")
      assert "per_test_coverage" in ready["capabilities"]
    end

    # A megabyte of JSON per worker is a cost the protocol lets us decline,
    # so past the limit the coverage goes to a file in the reni instead.
    test "coverage larger than the inline limit is spilled into the reni", %{reni: reni} do
      hello = JSON.decode!(hello(reni)) |> Map.put("inline_limit_bytes", 10) |> JSON.encode!()

      done =
        reply(
          drive_in(project("green"), reni, [
            hello,
            request("prepare", 2),
            request("baseline", 3),
            request("shutdown", 4)
          ]),
          "baseline_done"
        )

      refute Map.has_key?(done, "coverage")
      assert String.starts_with?(done["coverage_path"], reni)
      assert done["coverage_path"] |> File.read!() |> JSON.decode!() |> length() > 3
    end

    test "baseline before prepare is refused, and not fatally", %{reni: reni} do
      lines = summon(reni, "green", [~s({"type":"baseline","id":2})])
      error = reply(lines, "error")

      assert error["code"] == "not_prepared"
      assert error["fatal"] == false
    end
  end
end
