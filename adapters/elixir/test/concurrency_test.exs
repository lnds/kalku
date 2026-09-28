defmodule Kalku.ConcurrencyTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  defp sites_of(reni, spells) do
    lines =
      summon(reni, "concurrent", [
        request("prepare", 2),
        JSON.encode!(%{
          "type" => "sites",
          "id" => 3,
          "files" => ["lib/concurrent.ex"],
          "spells" => spells,
          "exclude_calls" => []
        })
      ])

    reply(lines, "sites_found")["sites"]
  end

  defp described(sites) do
    for s <- sites,
        do: "#{s["spell"]} #{s["enclosing"]}: #{s["original"]} -> #{s["replacement"]}"
  end

  describe "against a project whose decisions are about time and failure" do
    setup :a_reni

    # A deadline is the concept, not the number: what a survivor here says
    # is that no test observes the wait expiring.
    test "a wait with a deadline is a site, and the deadline is what changes", %{reni: reni} do
      found = described(sites_of(reni, ["await"]))

      assert "await Concurrent.wait_for/1: 50 -> 0" in found
      assert "await Concurrent.ask/1: 100 -> 0" in found
    end

    # What happens when a child dies is a decision, and a suite that
    # cannot tell one strategy from another has never killed one.
    test "a supervision decision is a site", %{reni: reni} do
      found = described(sites_of(reni, ["supervise"]))

      assert "supervise Concurrent.init/1: :one_for_one -> :one_for_all" in found
      assert "supervise Concurrent.helper_of/1: Process.link(pid) -> pid" in found
    end

    # Speed is the product and these are the slowest wekufe there are, so
    # a project asks for them by name or does not get them.
    test "neither spell is proposed unless it was asked for", %{reni: reni} do
      usual = sites_of(reni, ["arm", "compare", "connect", "negate", "literal", "call"])

      assert Enum.all?(usual, &(&1["spell"] not in ["await", "supervise"]))
      refute usual == []
    end

    test "the kalku announces the spells it can now cast", %{reni: reni} do
      ready = reply(summon(reni, "concurrent", []), "ready")

      assert "await" in ready["spells"]
      assert "supervise" in ready["spells"]
    end
  end
end
