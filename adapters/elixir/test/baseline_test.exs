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
               "test/green_test.exs:13",
               "test/green_test.exs:17",
               "test/green_test.exs:4",
               "test/green_test.exs:9",
               "test/same_line_test.exs:4"
             ]

      assert Enum.all?(done["tests"], &(&1["duration_ms"] >= 0))
    end

    # A mocking library sets itself up in `test_helper.exs` and tidies up in
    # an `after_suite` callback, which ExUnit runs at the end of every
    # `ExUnit.run`. The baseline runs the suite once whole and then once per
    # test, so that tidying left the per-test runs with the mock gone: the
    # test driving it failed, was credited with no lines at all, and the
    # attribution had to be withheld for the whole run.
    test "a suite that mocks its own modules is attributed test by test", %{reni: reni} do
      done = reply(run(reni, "mocked", ["baseline"]), "baseline_done")

      assert done["status"] == "green"
      assert length(done["tests"]) == 2

      by_line = Map.new(done["coverage"], &{{&1["file"], &1["line"]}, &1["tests"]})
      # `charge/1` runs in the test that drives the mock, and `total/2` in
      # the one that does not touch it.
      assert by_line[{"lib/mocked.ex", 5}] == ["test/mocked_test.exs:9"]
      assert by_line[{"lib/mocked.ex", 12}] == ["test/mocked_test.exs:5"]
    end

    # The net under it: where the lines the suite reached are not all
    # credited to some test, the attribution is withheld, because a wekufe
    # judged against too few tests is a survivor that is not a hole.
    test "attribution that does not add up to the suite is withheld" do
      whole = MapSet.new([{"lib/a.ex", 1}, {"lib/a.ex", 2}])
      credited = fn lines -> %{id: "t", lines: lines} end

      assert [%{lines: [{"lib/a.ex", 1}, {"lib/a.ex", 2}]}] =
               Kalku.Baseline.reconcile([credited.([{"lib/a.ex", 1}, {"lib/a.ex", 2}])], whole)

      assert [%{lines: []}] =
               Kalku.Baseline.reconcile([credited.([{"lib/a.ex", 1}])], whole)
    end

    # Withholding it for every project would give away the speed the whole
    # design is built on, so a suite that replaces nothing still gets the
    # attribution it earned.
    test "a suite that mocks nothing still reports coverage", %{reni: reni} do
      done = reply(run(reni, "green", ["baseline"]), "baseline_done")

      assert Map.has_key?(done, "coverage") or Map.has_key?(done, "coverage_path")
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
        |> Enum.map(&Kalku.Json.decode!/1)
        |> Enum.filter(&(&1["type"] == "baseline_done"))

      assert first["status"] == "green"
      assert second["status"] == "green"
      assert length(second["tests"]) == length(first["tests"])
    end

    # What the coverage is for: a wekufe on `classify` is cast against the
    # test that runs `classify`, not against the whole suite. The fixture
    # has a second module whose test is written on the same line as one
    # that does cover `classify`, which is what selecting by line alone
    # used to conflate.
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
    # A root that reaches the project through a symlink — `/tmp` is one on
    # macOS — used to leave every coverage entry absolute, matching no
    # site, so every wekufe came back uncovered.
    test "coverage is relative to the project however the root was named", %{reni: reni} do
      lines =
        drive_in(project("green"), reni, [
          hello(reni, Path.expand(project("green"))),
          request("prepare", 2),
          request("baseline", 3),
          request("shutdown", 4)
        ])

      done = reply(lines, "baseline_done")

      assert Enum.all?(done["coverage"], &(&1["file"] == "lib/green.ex"))
      assert Enum.all?(done["tests"], &(not String.starts_with?(&1["file"], "/")))
    end

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
      hello =
        Kalku.Json.decode!(hello(reni))
        |> Map.put("inline_limit_bytes", 10)
        |> Kalku.Json.encode()

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
      assert done["coverage_path"] |> File.read!() |> Kalku.Json.decode!() |> length() > 3
    end

    test "baseline before prepare is refused, and not fatally", %{reni: reni} do
      lines = summon(reni, "green", [~s({"type":"baseline","id":2})])
      error = reply(lines, "error")

      assert error["code"] == "not_prepared"
      assert error["fatal"] == false
    end
  end
end
