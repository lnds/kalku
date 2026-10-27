defmodule Kalku.ReturnsTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  describe "against a project whose functions give back what they compute" do
    setup :a_reni

    # A test that looks at a function's result kills the wekufe that gives
    # back `nil` instead; the spell is asked for by name.
    test "a function's result is a site, and a test that checks it kills it", %{reni: reni} do
      ready = reply(summon(reni, "green", []), "ready")
      assert "return" in ready["spells"]

      sites_request =
        Kalku.Json.encode(%{
          "type" => "sites",
          "id" => 3,
          "files" => ["lib/green.ex"],
          "spells" => ["return"],
          "exclude_calls" => []
        })

      sites =
        reply(summon(reni, "green", [request("prepare", 2), sites_request]), "sites_found")[
          "sites"
        ]

      assert Enum.all?(sites, &(&1["spell"] == "return"))

      total = Enum.find(sites, &(&1["enclosing"] == "Green.total/1"))
      assert total["original"] == "Enum.reduce(xs, 0, &+/2)"
      assert total["replacement"] == "nil"

      cast =
        Kalku.Json.encode(%{
          "type" => "cast",
          "id" => 5,
          "wekufe" => "total-nil",
          "site" => Map.take(total, ~w(site_id file span spell replacement reload)),
          "tests" => ["test/green_test.exs:9"]
        })

      lines = summon(reni, "green", [request("prepare", 2), request("baseline", 3), cast])

      assert reply(lines, "cast_done")["outcome"] == "killed"
    end
  end
end
