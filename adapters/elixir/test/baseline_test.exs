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
      assert length(done["tests"]) == 5

      by_line = Map.new(done["coverage"], &{{&1["file"], &1["line"]}, &1["tests"]})
      # `charge/1` runs in the test that drives the mock, and `total/2` in
      # the one that does not touch it.
      assert by_line[{"lib/mocked.ex", 5}] == ["test/mocked_test.exs:9"]
      assert by_line[{"lib/mocked.ex", 12}] == ["test/mocked_test.exs:5"]

      # A line run through the copy the library keeps of a module it replaced
      # is a line of that module's file. Named anything else it matches no
      # site, and a wekufe a test reaches is reported as reached by none.
      assert by_line[{"lib/mocked/fee.ex", 3}] == ["test/late_copy_test.exs:15"]
      assert Enum.all?(done["coverage"], &String.starts_with?(&1["file"], "lib/"))
    end

    # Replacing a module under `:cover` makes the mocking library export its
    # counters to a file in the project's root, which it removes in the same
    # `after_suite` callback the kalku leaves out.
    test "a suite that mocks its own modules leaves the project as it was", %{reni: reni} do
      before = File.ls!(project("mocked"))
      done = reply(run(reni, "mocked", ["baseline"]), "baseline_done")

      assert done["status"] == "green"
      assert File.ls!(project("mocked")) == before
    end

    # A test can set a library up itself, in its `setup`, and the library then
    # asks to tidy up after the suite from there: registered after the suite
    # was loaded, and run at the end of the same `ExUnit.run`. A mocking
    # library tidying up there reads back the file the kalku has already
    # taken out of the project, and the kalku went down with it.
    test "what a test registers to run after the suite is left out too", %{reni: reni} do
      before = File.ls!(project("mocked"))
      done = reply(run(reni, "mocked", ["baseline"]), "baseline_done")

      assert done["status"] == "green"
      assert "test/tidied_late_test.exs:11" in Enum.map(done["tests"], & &1["test"])
      assert "test/late_copy_test.exs:11" in Enum.map(done["tests"], & &1["test"])
      refute Process.get(:last_stderr) =~ "terminating"
      assert File.ls!(project("mocked")) == before
    end

    # The net under it: where the lines the suite reached are not all
    # credited to some test, the attribution is withheld, because a wekufe
    # judged against too few tests is a survivor that is not a hole.
    test "attribution that does not add up to the suite is withheld" do
      whole = MapSet.new([{"lib/a.ex", 1}, {"lib/a.ex", 2}])
      credited = fn lines -> %{id: "t", lines: lines} end

      assert {[%{lines: [{"lib/a.ex", 1}, {"lib/a.ex", 2}]}], nil} =
               Kalku.Baseline.reconcile([credited.([{"lib/a.ex", 1}, {"lib/a.ex", 2}])], whole)

      # And why is said, naming the line, for the report to carry.
      assert {[%{lines: []}], why} =
               Kalku.Baseline.reconcile([credited.([{"lib/a.ex", 1}])], whole)

      assert why =~ "1 line(s) that no single test is credited with (a.ex:2)"
    end

    # Only the kalku knows why, and a reader who is told "the whole suite"
    # and not why goes looking for a mock that is not there.
    test "a suite whose attribution is withheld says why in its answer", %{reni: reni} do
      done = reply(run(reni, "lazy", ["baseline"]), "baseline_done")

      assert done["status"] == "green"
      assert done["coverage_withheld"] =~ "no single test is credited with (lazy.ex:"
      refute Map.has_key?(done, "coverage")
      refute Map.has_key?(done, "coverage_packed_path")
    end

    # Withholding it for every project would give away the speed the whole
    # design is built on, so a suite that replaces nothing still gets the
    # attribution it earned.
    test "a suite that mocks nothing still reports coverage", %{reni: reni} do
      done = reply(run(reni, "green", ["baseline"]), "baseline_done")

      assert Map.has_key?(done, "coverage") or Map.has_key?(done, "coverage_packed_path")
    end

    # A suite that is already failing cannot say whether a wekufe was
    # noticed, so the baseline says so plainly rather than scoring it.
    test "a suite with a failing test is red, and the failure is quoted", %{reni: reni} do
      done = reply(run(reni, "red", ["baseline"]), "baseline_done")

      assert done["status"] == "red"

      assert [%{"test" => "test/red_test.exs:10", "message" => message}, unready] =
               done["failures"]

      # A test whose module fails in `setup_all` never runs. It is not a test
      # that passed, and what stopped it is said.
      assert unready["test"] == "test/unready_test.exs:9"
      assert unready["message"] =~ "could not be set up"
      assert unready["message"] =~ "nothing here can be set up"

      assert message =~ "Assertion with == failed"
      assert message =~ "left:  :negative"
      refute message =~ "stacktrace"

      # The test that passed is still reported: red is about the suite,
      # not about every test in it.
      assert length(done["tests"]) == 3
    end

    # A suite that is green under `mix test` and red here is red for a way
    # this run is unlike that one. This kalku copies nothing: what a test
    # can tell is that the build is somewhere else, and the refusal used to
    # speak of a copy of the project all the same.
    test "the baseline says how it was unlike `mix test`, and where the build is", %{reni: reni} do
      done = reply(run(reni, "red", ["baseline"]), "baseline_done")

      assert [build, loading] = done["differences"]
      assert build =~ Path.join(reni, "build")
      assert build =~ "MIX_BUILD_PATH"
      assert loading =~ "alias"
      refute Enum.any?(done["differences"], &(&1 =~ "copy"))
    end

    # Each test is run a second time, alone, to learn which lines it reaches.
    # A suite with a failing test used to end the run, so the second pass was
    # skipped for it. It is now measured with the tests that pass, and those
    # are chosen by the same coverage as in any other run.
    test "a suite with tests that pass is measured, the failing ones too", %{reni: reni} do
      done = reply(run(reni, "red", ["baseline"]), "baseline_done")

      assert done["status"] == "red"
      by_line = Map.new(done["coverage"], &{&1["line"], &1["tests"]})

      # The clause the passing test takes, and the one only the failing test
      # reaches: left uncredited, it would be a line the suite reached and
      # no test did, and the whole attribution would be withheld.
      assert "test/red_test.exs:5" in by_line[4]
      assert "test/red_test.exs:10" in by_line[5]
    end

    # That is for choosing the tests of a cast, and a suite in which nothing
    # passes has no casts: on a large suite the answer came hours after it
    # was known.
    test "a suite in which nothing passes is not run a second time", %{reni: reni} do
      lines =
        drive_in(nothing_passes(), reni, [
          hello(reni),
          request("prepare", 2),
          request("baseline", 3),
          request("shutdown", 4)
        ])

      done = reply(lines, "baseline_done")

      assert done["status"] == "red"
      refute Map.has_key?(done, "coverage")
      refute Map.has_key?(done, "coverage_path")
      refute Map.has_key?(done, "coverage_packed_path")

      said = String.split(Process.get(:last_stderr), "red: the test that used to pass ran")
      assert length(said) - 1 == 1
    end

    test "a test is run alone in its own module, not in all of them" do
      test = %Kalku.Baseline.Collector.Test{module: SomeTest, file: "test/some_test.exs", line: 3}

      assert Kalku.Baseline.holding(test, [SomeTest, OtherTest]) == [SomeTest]
      # A test nobody named the module of still has to be found.
      assert Kalku.Baseline.holding(%{test | module: nil}, [SomeTest, OtherTest]) ==
               [SomeTest, OtherTest]
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
      assert String.starts_with?(done["coverage_packed_path"], reni)

      # Packed: each test named once, and the lines the same tests reach
      # listed together. Written line by line, a project's coverage was the
      # same few thousand names a hundred megabytes over.
      packed = done["coverage_packed_path"] |> File.read!() |> Kalku.Json.decode!()
      assert "test/green_test.exs:4" in packed["tests"]
      assert length(packed["tests"]) == length(Enum.uniq(packed["tests"]))

      at = Enum.find_index(packed["tests"], &(&1 == "test/green_test.exs:4"))
      classify = Enum.find(packed["reached"], &(4 in &1["lines"]))
      assert classify["file"] == "lib/green.ex"
      assert at in classify["tests"]

      # And it says what the inline form says.
      inline = reply(run(reni, "green", ["baseline"]), "baseline_done")["coverage"]

      unpacked =
        for group <- packed["reached"], line <- group["lines"] do
          %{
            "file" => group["file"],
            "line" => line,
            "tests" => group["tests"] |> Enum.map(&Enum.at(packed["tests"], &1)) |> Enum.sort()
          }
        end

      assert Enum.sort_by(unpacked, &{&1["file"], &1["line"]}) == inline
    end

    test "baseline before prepare is refused, and not fatally", %{reni: reni} do
      lines = summon(reni, "green", [~s({"type":"baseline","id":2})])
      error = reply(lines, "error")

      assert error["code"] == "not_prepared"
      assert error["fatal"] == false
    end
  end

  # The `red` fixture with its one passing test made to fail.
  defp nothing_passes do
    dir = Path.join(System.tmp_dir!(), "kalku-hopeless-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf!(dir) end)
    File.cp_r!(project("red"), dir)
    File.rm_rf!(Path.join(dir, "_build"))

    rewrite(Path.join(dir, "mix.exs"), ~s({:kalku_elixir, path: "../../.."}), "")

    rewrite(
      Path.join(dir, "test/red_test.exs"),
      "the passing test ran",
      "the test that used to pass ran"
    )

    rewrite(
      Path.join(dir, "test/red_test.exs"),
      "classify(1) == :non_negative",
      "classify(1) == :negative"
    )

    dir
  end

  defp rewrite(path, was, now) do
    text = File.read!(path)
    true = String.contains?(text, was)
    File.write!(path, String.replace(text, was, now, global: false))
  end
end
