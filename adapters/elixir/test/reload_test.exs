defmodule Kalku.ReloadTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  setup :a_reni

  describe "what depends on what" do
    test "a site in a macro module is marked `dependents`, an ordinary one is not", %{reni: reni} do
      found =
        reply(
          summon(reni, "macros", [
            request("prepare", 2),
            ~s({"type":"sites","id":3,"files":["lib/greeter.ex","lib/door.ex"],"spells":["literal"],"exclude_calls":[]})
          ]),
          "sites_found"
        )

      by_file = for s <- found["sites"], into: %{}, do: {s["file"], s["reload"]}

      # `Door` uses `Greeter`'s macro, so changing Greeter means
      # recompiling Door — and the kaikai side is told so it can budget it.
      assert by_file["lib/greeter.ex"] == "dependents"
      assert by_file["lib/door.ex"] == "module"
    end

    test "reload brings the changed file and everything stitched into it", %{reni: reni} do
      done =
        reply(
          summon(reni, "macros", [
            request("prepare", 2),
            ~s({"type":"reload","id":3,"files":["lib/greeter.ex"]})
          ]),
          "reloaded"
        )

      assert done["modules"] == ["Greeter"]
      assert done["dependents"] == ["Door"]
      assert done["duration_ms"] >= 0
    end
  end

  # The reason `dependents` exists, shown rather than described: the same
  # wekufe, the same test, and the only difference is whether the module
  # that expanded the macro was recompiled.
  test "a macro wekufe is a false survivor unless its dependents are recompiled", %{reni: reni} do
    {from, to} = greeting_literal()

    lines =
      summon(reni, "macros", [
        request("prepare", 2),
        request("baseline", 3),
        cast("as-module", 4, {from, to}, "module"),
        cast("as-dependents", 5, {from, to}, "dependents")
      ])

    casts =
      for line <- lines,
          {:ok, %{"wekufe" => w} = d} <- [Kalku.Json.decode(line)],
          into: %{},
          do: {w, d}

    # Loaded, but nobody was running it: Door still held the old
    # expansion, so no test could have noticed.
    assert casts["as-module"]["outcome"] == "survived"

    # Recompiled into its dependent, and killed at once.
    assert casts["as-dependents"]["outcome"] == "killed"
    assert casts["as-dependents"]["killed_by"] == "test/door_test.exs:4"
  end

  test "the kalku claims recompile_dependents only now that it can", %{reni: reni} do
    ready = reply(summon(reni, "macros", []), "ready")
    assert "recompile_dependents" in ready["capabilities"]
  end

  defp greeting_literal do
    source = File.read!(Path.join(project("macros"), "lib/greeter.ex"))
    {from, length} = :binary.match(source, ~s("hello, "))
    {from, from + length}
  end

  defp cast(wekufe, id, {from, to}, reload) do
    Kalku.Json.encode(%{
      "type" => "cast",
      "id" => id,
      "wekufe" => wekufe,
      "site" => %{
        "site_id" => "m#{id}",
        "file" => "lib/greeter.ex",
        "span" => %{
          "start" => %{"line" => 1, "col" => 1, "byte" => from},
          "end" => %{"line" => 1, "col" => 1, "byte" => to}
        },
        "spell" => "literal",
        "replacement" => ~s("bye, "),
        "reload" => reload
      },
      "tests" => ["test/door_test.exs:4"]
    })
  end
end
