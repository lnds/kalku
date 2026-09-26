defmodule Kalku.ResetTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  # `Application.put_env(:green, :remembered, value)`; this is the value.
  @remembered {729, 734}
  @remembering ["test/green_test.exs:17"]

  # `Enum.reduce(xs, 0, ...)`, killed by the test that adds.
  @seed {214, 215}
  @adding ["test/green_test.exs:9"]

  setup :a_reni

  test "a cast that leaves state behind says so, and a reset puts it back", %{reni: reni} do
    lines =
      summon(reni, "green", [
        request("prepare", 2),
        request("baseline", 3),
        cast("dirtier", 4, @remembered, ":twice", @remembering),
        request("reset", 5),
        cast("after-reset", 6, @seed, "1", @adding)
      ])

    casts =
      for line <- lines, {:ok, %{"wekufe" => w} = d} <- [JSON.decode(line)], into: %{}, do: {w, d}

    # The wekufe changed application env, and the kalku noticed.
    assert casts["dirtier"]["dirty"] == true

    assert reply(lines, "reset_done")["clean"] == true

    # The runtime is usable afterwards — the point of resetting in place
    # rather than being recycled — and clean again.
    assert casts["after-reset"]["outcome"] == "killed"
    assert casts["after-reset"]["dirty"] == false
  end

  test "a cast that touches nothing is not called dirty", %{reni: reni} do
    done =
      reply(
        summon(reni, "green", [
          request("prepare", 2),
          request("baseline", 3),
          cast("clean", 4, @seed, "1", @adding)
        ]),
        "cast_done"
      )

    assert done["outcome"] == "killed"
    assert done["dirty"] == false
  end

  test "reset before prepare is refused, and not fatally", %{reni: reni} do
    error = reply(summon(reni, "green", [request("reset", 2)]), "error")

    assert error["code"] == "not_prepared"
    assert error["fatal"] == false
  end

  test "the kalku claims reset only now that it can do it", %{reni: reni} do
    ready = reply(summon(reni, "green", []), "ready")
    assert "reset" in ready["capabilities"]
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
        "spell" => "literal",
        "replacement" => replacement,
        "reload" => "module"
      },
      "tests" => tests
    })
  end
end
