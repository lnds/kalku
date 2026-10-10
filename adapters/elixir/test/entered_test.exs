defmodule Kalku.EnteredTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  @file_under_test "lib/started/counter.ex"
  @suite [
    "test/counter_test.exs:4",
    "test/counter_test.exs:8",
    "test/counter_test.exs:12",
    "test/counter_test.exs:16"
  ]

  # A cast runs in a runtime that has already started, so the code that
  # started it does not run again: a wekufe there is loaded and never met,
  # and every test passes for that alone.
  describe "a wekufe in code that runs once" do
    setup :a_reni

    test "is not a survivor, and a wekufe the tests ran is judged as before", %{reni: reni} do
      lines =
        summon(reni, "started", [
          request("prepare", 2),
          request("baseline", 3),
          cast("at-start", 4, {"__MODULE__, ", "0"}, "1", "Started.Counter.start_link/1"),
          cast("unnoticed", 5, {"if(n ", ">"}, ">=", "Started.Counter.sign/1"),
          cast("noticed", 6, {"n * ", "2"}, "3", "Started.Counter.double/1"),
          cast("in-a-callback", 7, {"{:reply, ", "7"}, "8", "Started.Counter.handle_call/3")
        ])

      by_wekufe =
        for line <- lines,
            {:ok, d} <- [Kalku.Json.decode(line)],
            d["wekufe"],
            into: %{},
            do: {d["wekufe"], d}

      # A test asserts the value the counter starts with. Under `mix test`
      # this wekufe fails it; here nothing ran the wekufe at all.
      assert by_wekufe["at-start"]["outcome"] == "no_coverage"
      assert by_wekufe["at-start"]["message"] =~ "no test ran `Started.Counter.start_link/1`"

      # Run by a test and noticed by none: a hole, and it stays one.
      assert by_wekufe["unnoticed"]["outcome"] == "survived"
      assert by_wekufe["noticed"]["outcome"] == "killed"

      # A callback is called by the process that owns it, not by the test,
      # and that is a call all the same.
      assert by_wekufe["in-a-callback"]["outcome"] == "survived"
    end
  end

  defp cast(wekufe, id, {lead, target}, replacement, enclosing) do
    source = File.read!(Path.join(project("started"), @file_under_test))
    {at, _} = :binary.match(source, lead <> target)
    from = at + byte_size(lead)

    Kalku.Json.encode(%{
      "type" => "cast",
      "id" => id,
      "wekufe" => wekufe,
      "site" => %{
        "site_id" => wekufe,
        "file" => @file_under_test,
        "enclosing" => enclosing,
        "span" => %{
          "start" => %{"line" => 1, "col" => 1, "byte" => from},
          "end" => %{"line" => 1, "col" => 1, "byte" => from + byte_size(target)}
        },
        "spell" => "literal",
        "replacement" => replacement,
        "reload" => "module"
      },
      "tests" => @suite
    })
  end
end
