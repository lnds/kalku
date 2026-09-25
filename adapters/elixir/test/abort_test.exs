defmodule Kalku.AbortTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  # `count_from(n, limit) when n >= limit` is the clause that stops the
  # counting. A wekufe that pushes the limit out of reach makes the test
  # that calls it run forever — which is what `abort` is for.
  @guard {485, 500}
  @forever "when n >= limit * 0 + 99_999_999_999"
  @counting ["test/green_test.exs:13"]

  # `Enum.reduce(xs, 0, ...)`, killed by the test that adds.
  @seed {214, 215}
  @adding ["test/green_test.exs:9"]

  setup :a_reni

  test "a wekufe that never finishes is aborted in place, and the kalku serves the next one", %{
    reni: reni
  } do
    lines =
      summon(reni, "green", [
        request("prepare", 2),
        request("baseline", 3),
        cast("looping", 4, @guard, @forever, @counting),
        ~s({"type":"abort","id":5,"cast":4}),
        cast("after-abort", 6, @seed, "1", @adding)
      ])

    aborted = reply(lines, "aborted")
    assert aborted["cast"] == 4
    # Warm, not recycled: this is the whole point of aborting.
    assert aborted["restored"] == true

    # The aborted cast reports nothing. The abort was its answer, and a
    # `cast_done` after it would be a second one.
    refute Enum.any?(lines, fn line ->
             match?({:ok, %{"wekufe" => "looping"}}, JSON.decode(line))
           end)

    done = reply(lines, "cast_done")
    assert done["wekufe"] == "after-abort"
    assert done["outcome"] == "killed"
  end

  # A cast can finish a moment before the abort chasing it arrives. That
  # is a race the kaikai side cannot avoid, so it must not be an error.
  test "an abort with nothing to stop is answered, not refused", %{reni: reni} do
    lines = summon(reni, "green", [request("prepare", 2), ~s({"type":"abort","id":3,"cast":99})])

    aborted = reply(lines, "aborted")
    assert aborted["cast"] == 99
    assert aborted["restored"] == true
  end

  test "the kalku claims abort only now that it can do it", %{reni: reni} do
    ready = reply(summon(reni, "green", []), "ready")
    assert "abort" in ready["capabilities"]
  end

  # Leaving without it would have the kaikai side report a wekufe as
  # crashed when it was measured and merely unreported.
  test "shutdown waits for a cast already under way", %{reni: reni} do
    lines =
      summon(reni, "green", [
        request("prepare", 2),
        request("baseline", 3),
        cast("in-flight", 4, @seed, "1", @adding)
      ])

    assert reply(lines, "cast_done")["wekufe"] == "in-flight"
    assert List.last(lines) |> JSON.decode!() |> Map.get("type") == "bye"
  end

  defp cast(wekufe, id, {from, to}, replacement, tests) do
    JSON.encode!(%{
      "type" => "cast",
      "id" => id,
      "wekufe" => wekufe,
      "site" => %{
        "site_id" => "s#{from}",
        "file" => "lib/green.ex",
        "span" => %{
          "start" => %{"line" => 1, "col" => 1, "byte" => from},
          "end" => %{"line" => 1, "col" => 1, "byte" => to}
        },
        "spell" => "compare",
        "replacement" => replacement,
        "reload" => "module"
      },
      "tests" => tests
    })
  end
end
