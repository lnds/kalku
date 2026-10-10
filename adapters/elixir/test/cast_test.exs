defmodule Kalku.CastTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  alias Kalku.Cast

  # `lib/green.ex` holds `Enum.reduce(xs, 0, &+/2)`; this is the `0`.
  @seed {214, 215}
  @covering ["test/green_test.exs:9"]

  describe "splicing" do
    test "a span is replaced exactly, in bytes" do
      source = "defmodule A do\n  def f, do: 0\nend\n"
      from = :binary.match(source, "0") |> elem(0)

      assert {:ok, spliced} = Cast.splice(source, site(from, from + 1, "1"))
      assert spliced == String.replace(source, "0", "1")
    end

    # A site that does not fit the file is a site from another version of
    # it. Splicing anyway would cast a wekufe nobody designed.
    test "a span outside the file is refused rather than guessed at" do
      assert {:error, message} = Cast.splice("short", site(2, 99, "x"))
      assert message =~ "not inside"
    end

    test "a site with no span is refused" do
      assert {:error, message} = Cast.splice("anything", %{"file" => "lib/a.ex"})
      assert message =~ "no span"
    end
  end

  describe "against a real project" do
    setup :a_reni

    test "every outcome a cast can have, and the runtime survives all of them", %{reni: reni} do
      lines =
        summon(reni, "green", [
          request("prepare", 2),
          request("baseline", 3),
          cast("killed", 4, @seed, "1", @covering),
          cast("equivalent", 5, @seed, "0", @covering),
          cast("broken", 6, @seed, "@", @covering),
          cast("after-all", 7, @seed, "1", @covering)
        ])

      by_wekufe =
        for line <- lines,
            {:ok, d} <- [Kalku.Json.decode(line)],
            d["wekufe"],
            into: %{},
            do: {d["wekufe"], d}

      assert by_wekufe["killed"]["outcome"] == "killed"
      assert by_wekufe["killed"]["killed_by"] == "test/green_test.exs:9"

      # Proved, not guessed: the compiled code is the original's, so no
      # test could have noticed and none was run.
      assert by_wekufe["equivalent"]["outcome"] == "equivalent"

      assert by_wekufe["broken"]["outcome"] == "compile_error"
      assert by_wekufe["broken"]["message"] =~ "lib/green.ex"

      # The one that matters most: a cast that failed to compile left a
      # runtime the next cast can still use.
      assert by_wekufe["after-all"]["outcome"] == "killed"
    end

    # Where the kalku doubted its coverage, the tests credited with a line
    # may be missing one. They are asked first, and the others only when
    # none of them notices: one failing test is a kill whoever else was
    # asked, and a survivor is one only when nobody was left to ask.
    test "the rest of the suite is asked when the first tests do not notice", %{reni: reni} do
      elsewhere = ["test/same_line_test.exs:5"]

      lines =
        summon(reni, "green", [
          request("prepare", 2),
          request("baseline", 3),
          cast("alone", 4, @seed, "1", elsewhere),
          with_rest(cast("with-the-rest", 5, @seed, "1", elsewhere), @covering),
          with_rest(cast("first-is-enough", 6, @seed, "1", @covering), elsewhere)
        ])

      by_wekufe =
        for line <- lines,
            {:ok, d} <- [Kalku.Json.decode(line)],
            d["wekufe"],
            into: %{},
            do: {d["wekufe"], d}

      assert by_wekufe["alone"]["outcome"] == "survived"
      assert by_wekufe["with-the-rest"]["outcome"] == "killed"
      assert by_wekufe["with-the-rest"]["killed_by"] == "test/green_test.exs:9"
      assert by_wekufe["first-is-enough"]["killed_by"] == "test/green_test.exs:9"
    end

    # The kaikai side asks one kalku for the baseline and hands every
    # worker the answer, so a kalku in a pool is asked to cast without ever
    # having been asked for a baseline. It still has to be able to kill.
    test "a kalku that was only prepared can still kill", %{reni: reni} do
      done =
        reply(
          summon(reni, "green", [
            request("prepare", 2),
            cast("killed", 3, @seed, "1", @covering)
          ]),
          "cast_done"
        )

      assert done["outcome"] == "killed"
      assert done["killed_by"] == "test/green_test.exs:9"
    end

    # Nothing ran, so nothing was measured. `survived` here would count a
    # hole nobody looked for as one somebody looked for and did not find,
    # which is the mistake that makes every other number worthless.
    test "a cast whose tests never ran does not claim the wekufe survived", %{reni: reni} do
      done =
        reply(
          summon(reni, "green", [
            request("prepare", 2),
            cast("unjudged", 3, @seed, "1", ["test/no_such_test.exs:1"])
          ]),
          "cast_done"
        )

      assert done["outcome"] == "crashed"
      assert done["message"] =~ "selected test"
    end

    # A wekufe the suite genuinely does not notice. `classify` is mutated
    # from `>=` to `>`, and the tests only ever pass 1 and -1 — never the
    # zero where the two differ.
    test "a wekufe no test notices survives, and says so", %{reni: reni} do
      gt = comparison_site()

      done =
        reply(
          summon(reni, "green", [
            request("prepare", 2),
            request("baseline", 3),
            cast("boundary", 4, gt, "n > 0", ["test/green_test.exs:4"])
          ]),
          "cast_done"
        )

      assert done["outcome"] == "survived"
      assert done["code_hash"] != nil
    end

    test "a cast writes nothing into the project", %{reni: reni} do
      before = File.read!(Path.join(project("green"), "lib/green.ex"))

      summon(reni, "green", [
        request("prepare", 2),
        request("baseline", 3),
        cast("killed", 4, @seed, "1", @covering)
      ])

      assert File.read!(Path.join(project("green"), "lib/green.ex")) == before
      refute File.exists?(Path.join(project("green"), "_build"))
    end

    test "the kalku claims what it can now do", %{reni: reni} do
      ready = reply(summon(reni, "green", []), "ready")

      assert "code_hash" in ready["capabilities"]
      assert "hot_load" in ready["capabilities"]
    end
  end

  # ---- requests -----------------------------------------------------

  describe "against a suite that mocks one of its own modules" do
    setup :a_reni

    # The bug this guards: a mocking library tidies up in an `after_suite`
    # callback, ExUnit runs it at the end of every `ExUnit.run`, and the
    # runtime runs the suite again for every cast. The test driving the mock
    # then failed on code that was never touched, and every wekufe of
    # `charge/1` came back `killed` — a kill that no test had earned. A
    # wekufe the suite cannot tell from the original has to survive.
    test "a wekufe no test can tell from the original survives, and a real one is killed",
         %{reni: reni} do
      source = File.read!(Path.join(project("mocked"), "lib/mocked.ex"))
      {from, _} = :binary.match(source, "amount > 0")
      # `amount > 0` to `amount >= 0`: `charge(1)` answers the same.
      kept = mocked_cast("kept", 4, from + 7, from + 8, ">=", 5)
      # `amount > 0` to `amount < 0`: `charge(1)` now answers `:error`.
      broken = mocked_cast("broken", 5, from + 7, from + 8, "<", 5)

      lines =
        summon(reni, "mocked", [
          request("prepare", 2),
          request("baseline", 3),
          kept,
          broken
        ])

      done = for l <- lines, {:ok, d} <- [Kalku.Json.decode(l)], d["type"] == "cast_done", do: d
      by_wekufe = Map.new(done, &{&1["wekufe"], &1})

      assert by_wekufe["kept"]["outcome"] == "survived"
      assert by_wekufe["broken"]["outcome"] == "killed"
      assert by_wekufe["broken"]["killed_by"] == "test/mocked_test.exs:9"
    end

    # Stopping `:cover` after the baseline gives each module back by loading
    # it from its file, and the copy a mocking library keeps of the module it
    # replaced has none: it was gone, the mock stood for nothing, and every
    # test driving it failed in every cast that did not compile that module
    # again. A wekufe was then killed by a test that never reached it.
    test "a test that drives a mock does not kill a wekufe it never reaches", %{reni: reni} do
      # `charge/1`, against the test that mocks `Mocked.Fee` and calls nothing else.
      elsewhere =
        replacing(4, "lib/mocked.ex", {"amount ", ">"}, ">=", 5, "test/late_copy_test.exs:11")

      # `Mocked.Fee`, against the test that mocks `Mocked.Rate`.
      other =
        replacing(4, "lib/mocked/fee.ex", {"amount, ", "10"}, "20", 3, "test/mocked_test.exs:9")

      # One kalku each, so that neither cast is judged in a runtime the other
      # has compiled in.
      for cast <- [elsewhere, other] do
        lines = summon(reni, "mocked", [request("prepare", 2), request("baseline", 3), cast])
        assert reply(lines, "cast_done")["outcome"] == "survived"
      end
    end

    # A cast keeps the modules of its file to put them back, and a module
    # that is a mock when the cast starts was not among them: the wekufe was
    # compiled over the mock and stayed loaded for the rest of the run, where
    # the next cast was judged with it.
    test "a wekufe in a module the suite mocks does not outlive its cast", %{reni: reni} do
      real = "test/late_copy_test.exs:15"
      fee = {"lib/mocked/fee.ex", {"amount, ", "10"}, "20", 3}
      total = {"lib/mocked.ex", {"a ", "+"}, "-", 12}

      lines =
        summon(reni, "mocked", [
          request("prepare", 2),
          request("baseline", 3),
          # The test that takes the real fee notices a fee that changed,
          cast_of(4, fee, real),
          # and not a total it never asks for, once that fee is back.
          cast_of(5, total, real)
        ])

      done = for l <- lines, {:ok, d} <- [Kalku.Json.decode(l)], d["type"] == "cast_done", do: d
      assert Enum.map(done, & &1["outcome"]) == ["killed", "survived"]
    end

    # A mocking library builds the copy it keeps of a module from the module's
    # file, where the original is. A test that drove a mock of the wekufe's
    # module put the original back behind it, and the tests after it never
    # met the wekufe: it survived a test that kills it.
    test "a wekufe is still there after a test has mocked its module", %{reni: reni} do
      before = File.ls!(project("mocked"))
      fee = {"lib/mocked/fee.ex", {"amount, ", "10"}, "20", 3}
      # The first mocks `Mocked.Fee`, the second takes the real fee.
      both = ["test/late_copy_test.exs:11", "test/late_copy_test.exs:15"]

      lines =
        summon(reni, "mocked", [
          request("prepare", 2),
          request("baseline", 3),
          cast_of(4, fee, both)
        ])

      done = reply(lines, "cast_done")
      assert done["outcome"] == "killed"
      assert done["killed_by"] == "test/late_copy_test.exs:15"
      assert File.ls!(project("mocked")) == before
    end
  end

  describe "against a suite that sets itself up once" do
    setup :a_reni

    # ExUnit does not run the tests of a module whose `setup_all` raised: it
    # reports them invalid. Read as tests that passed, a wekufe that stops a
    # whole module from running survived it.
    test "a wekufe that breaks `setup_all` is killed by the tests it stopped", %{reni: reni} do
      source = File.read!(Path.join(project("set_up"), "lib/set_up.ex"))
      {from, 2} = :binary.match(source, "10")

      wekufe =
        Kalku.Json.encode(%{
          "type" => "cast",
          "id" => 4,
          "wekufe" => "fewer",
          "site" => %{
            "site_id" => "fewer",
            "file" => "lib/set_up.ex",
            "span" => %{
              "start" => %{"line" => 4, "col" => 1, "byte" => from},
              "end" => %{"line" => 4, "col" => 1, "byte" => from + 2}
            },
            "spell" => "literal",
            "replacement" => "9",
            "reload" => "module"
          },
          "tests" => ["test/set_up_test.exs:11"]
        })

      lines = summon(reni, "set_up", [request("prepare", 2), request("baseline", 3), wekufe])

      assert reply(lines, "baseline_done")["status"] == "green"
      done = reply(lines, "cast_done")
      assert done["outcome"] == "killed"
      assert done["killed_by"] == "test/set_up_test.exs:11"
    end
  end

  defp cast_of(id, {file, place, replacement, line}, test),
    do: replacing(id, file, place, replacement, line, test)

  # A wekufe that replaces `target` where it follows `lead`.
  defp replacing(id, file, {lead, target}, replacement, line, test) do
    source = File.read!(Path.join(project("mocked"), file))
    {at, _} = :binary.match(source, lead <> target)
    from = at + byte_size(lead)
    to = from + byte_size(target)

    Kalku.Json.encode(%{
      "type" => "cast",
      "id" => id,
      "wekufe" => "w#{id}",
      "site" => %{
        "site_id" => "w#{id}",
        "file" => file,
        "span" => %{
          "start" => %{"line" => line, "col" => 1, "byte" => from},
          "end" => %{"line" => line, "col" => 1, "byte" => to}
        },
        "spell" => "compare",
        "replacement" => replacement,
        "reload" => "module"
      },
      "tests" => List.wrap(test)
    })
  end

  defp mocked_cast(wekufe, id, from, to, replacement, line) do
    Kalku.Json.encode(%{
      "type" => "cast",
      "id" => id,
      "wekufe" => wekufe,
      "site" => %{
        "site_id" => wekufe,
        "file" => "lib/mocked.ex",
        "span" => %{
          "start" => %{"line" => line, "col" => 1, "byte" => from},
          "end" => %{"line" => line, "col" => 1, "byte" => to}
        },
        "spell" => "compare",
        "replacement" => replacement,
        "reload" => "module"
      },
      "tests" => ["test/mocked_test.exs:5", "test/mocked_test.exs:9"]
    })
  end

  defp cast(wekufe, id, {from, to}, replacement, tests) do
    Kalku.Json.encode(%{
      "type" => "cast",
      "id" => id,
      "wekufe" => wekufe,
      "site" => site(from, to, replacement),
      "tests" => tests
    })
  end

  defp with_rest(cast, rest) do
    cast |> Kalku.Json.decode!() |> Map.put("rest", rest) |> Kalku.Json.encode()
  end

  defp site(from, to, replacement) do
    %{
      "site_id" => "s#{from}",
      "file" => "lib/green.ex",
      "span" => %{
        "start" => %{"line" => 1, "col" => 1, "byte" => from},
        "end" => %{"line" => 1, "col" => 1, "byte" => to}
      },
      "spell" => "literal",
      "replacement" => replacement,
      "reload" => "module"
    }
  end

  defp comparison_site do
    source = File.read!(Path.join(project("green"), "lib/green.ex"))
    {from, length} = :binary.match(source, "n >= 0")
    {from, from + length}
  end
end
