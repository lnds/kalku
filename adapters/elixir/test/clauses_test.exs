defmodule Kalku.ClausesTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  # A line is counted when the body on it runs. A guard, a pattern or a
  # condition is evaluated by calls that never run that line: the test that
  # tells `>` from `>=` is the one whose input is turned away, and asked of
  # lines alone it is not among the tests of the comparison. Every one of
  # these came back `survived`, with the advice to add the test that was
  # already there.
  describe "a comparison whose boundary is tested through another clause" do
    setup :a_reni

    test "is credited to every test that calls its function", %{reni: reni} do
      done = reply(run(reni, "clauses", ["baseline"]), "baseline_done")
      by_line = Map.new(done["coverage"], &{&1["line"], &1["tests"]})

      # The condition of a `cond`, and the guard of a `case` arm.
      assert by_line[8] == ["test/clauses_test.exs:4", "test/clauses_test.exs:5"]
      assert by_line[15] == ["test/clauses_test.exs:7", "test/clauses_test.exs:8"]

      # The guard of a function, by a call that matched no clause at all.
      assert by_line[20] == ["test/clauses_test.exs:10", "test/clauses_test.exs:12"]
      assert by_line[24] == ["test/clauses_test.exs:15", "test/clauses_test.exs:16"]

      # A body whose only expression carries the line of its head.
      assert by_line[21] == ["test/clauses_test.exs:10", "test/clauses_test.exs:12"]

      # A function nobody calls is still reached by no test.
      refute Map.has_key?(by_line, 26)
    end

    test "is killed by the test of the boundary", %{reni: reni} do
      done = reply(run(reni, "clauses", ["baseline"]), "baseline_done")
      tests = fn line -> Enum.find(done["coverage"], &(&1["line"] == line))["tests"] end

      lines =
        summon(reni, "clauses", [
          request("prepare", 2),
          cast(3, 8, "n ", tests.(8)),
          cast(4, 15, "when n ", tests.(15)),
          cast(5, 20, "amount) when amount ", tests.(20)),
          cast(6, 24, "pay(amount) when amount ", tests.(24))
        ])

      killers =
        lines
        |> Enum.map(&Kalku.Json.decode!/1)
        |> Enum.filter(&(&1["type"] == "cast_done"))
        |> Enum.map(&{&1["outcome"], &1["killed_by"]})

      assert killers == [
               {"killed", "test/clauses_test.exs:5"},
               {"killed", "test/clauses_test.exs:8"},
               {"killed", "test/clauses_test.exs:12"},
               {"killed", "test/clauses_test.exs:16"}
             ]
    end
  end

  # `>` made `>=`, where it follows this text.
  defp cast(id, line, lead, tests) do
    file = "lib/clauses.ex"
    source = File.read!(Path.join(project("clauses"), file))
    {at, _} = :binary.match(source, lead <> ">")
    from = at + byte_size(lead)

    Kalku.Json.encode(%{
      "type" => "cast",
      "id" => id,
      "wekufe" => "w#{id}",
      "site" => %{
        "site_id" => "w#{id}",
        "file" => file,
        "span" => %{
          "start" => %{"line" => line, "col" => 1, "byte" => from},
          "end" => %{"line" => line, "col" => 1, "byte" => from + 1}
        },
        "spell" => "compare",
        "replacement" => ">=",
        "reload" => "module"
      },
      "tests" => tests
    })
  end
end
