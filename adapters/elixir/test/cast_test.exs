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
            {:ok, d} <- [JSON.decode(line)],
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

  defp cast(wekufe, id, {from, to}, replacement, tests) do
    JSON.encode!(%{
      "type" => "cast",
      "id" => id,
      "wekufe" => wekufe,
      "site" => site(from, to, replacement),
      "tests" => tests
    })
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
