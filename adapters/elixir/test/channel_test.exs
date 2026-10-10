defmodule Kalku.ChannelTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  # The protocol is UTF-8, and a project's text is whatever its authors
  # speak. Only a real pipe shows what reaches the other side.
  describe "a project whose text is not ASCII" do
    setup :a_reni

    test "is quoted as it is written, and asked about in its own words", %{reni: reni} do
      source = File.read!(Path.join(project("spoken"), "lib/spoken.ex"))
      {from, size} = :binary.match(source, ~s("Búho sabio"))

      lines =
        summon(reni, "spoken", [
          request("prepare", 2),
          request("baseline", 3),
          Kalku.Json.encode(%{
            "type" => "sites",
            "id" => 4,
            "files" => ["lib/spoken.ex"],
            "spells" => ["literal"],
            "exclude_calls" => []
          }),
          Kalku.Json.encode(%{
            "type" => "cast",
            "id" => 5,
            "wekufe" => "w5",
            "site" => %{
              "site_id" => "w5",
              "file" => "lib/spoken.ex",
              "span" => %{
                "start" => %{"line" => 2, "col" => 22, "byte" => from},
                "end" => %{"line" => 2, "col" => 34, "byte" => from + size}
              },
              "spell" => "literal",
              "replacement" => ~s("Ñandú 🦤"),
              "reload" => "module"
            },
            "tests" => ["test/spoken_test.exs:4"]
          })
        ])

      originals = for s <- reply(lines, "sites_found")["sites"], do: s["original"]
      assert ~s("Búho sabio") in originals
      assert ~s("🔁") in originals

      assert reply(lines, "baseline_done")["status"] == "green"

      # The replacement arrives with its accents, or the wekufe would not be
      # the one that was asked for.
      assert reply(lines, "cast_done")["outcome"] == "killed"
    end
  end
end
