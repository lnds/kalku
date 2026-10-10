defmodule Kalku.EnteredTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  # A cast runs in a runtime that has already started, so the code that
  # started it does not run again: a wekufe there is loaded and never met,
  # and every test passes for that alone. Such a wekufe is tried with the
  # application started again, which is where it runs.
  describe "a wekufe in code that runs as the application starts" do
    setup :a_reni

    @counter "lib/started/counter.ex"
    @suite [
      "test/counter_test.exs:4",
      "test/counter_test.exs:8",
      "test/counter_test.exs:12",
      "test/counter_test.exs:16"
    ]

    test "is judged by the tests, with the application started on it", %{reni: reni} do
      cast = fn wekufe, id, at, replacement, enclosing ->
        cast("started", @counter, @suite, wekufe, id, at, replacement, enclosing)
      end

      lines =
        summon(reni, "started", [
          request("prepare", 2),
          request("baseline", 3),
          cast.("at-start", 4, {"__MODULE__, ", "0"}, "1", "Started.Counter.start_link/1"),
          cast.(
            "unnoticed-at-start",
            5,
            {":label, ", ~s("counter")},
            ~s(""),
            "Started.Counter.init/1"
          ),
          cast.(
            "does-not-start",
            6,
            {"def init(n) do\n    ", "Process.put(:label, \"counter\")\n    {:ok, n}"},
            ":oops",
            "Started.Counter.init/1"
          ),
          cast.("unnoticed", 7, {"if(n ", ">"}, ">=", "Started.Counter.sign/1"),
          cast.("noticed", 8, {"n * ", "2"}, "3", "Started.Counter.double/1"),
          cast.("in-a-callback", 9, {"{:reply, ", "7"}, "8", "Started.Counter.handle_call/3")
        ])

      by_wekufe = by_wekufe(lines)

      # A test asserts the value the counter starts with, and it is the
      # test that kills the wekufe, as it would under `mix test`.
      assert by_wekufe["at-start"]["outcome"] == "killed"
      assert by_wekufe["at-start"]["killed_by"] == "test/counter_test.exs:4"

      # Run as the application starts, and noticed by no test: a hole.
      assert by_wekufe["unnoticed-at-start"]["outcome"] == "survived"

      # An application that does not start is a suite that does not run.
      assert by_wekufe["does-not-start"]["outcome"] == "killed"
      assert by_wekufe["does-not-start"]["message"] =~ "does not start with the wekufe"

      # The application is the original's again for the casts that follow:
      # the counter starts at zero, or every one of these would be a kill.
      assert by_wekufe["unnoticed"]["outcome"] == "survived"
      assert by_wekufe["noticed"]["outcome"] == "killed"
      assert by_wekufe["noticed"]["killed_by"] == "test/counter_test.exs:12"

      # A callback is called by the process that owns it, not by the test,
      # and that is a call all the same.
      assert by_wekufe["in-a-callback"]["outcome"] == "survived"

      for {_, done} <- by_wekufe, do: assert(done["dirty"] == false)
    end

    # No test reaches what runs as the application starts, and every test
    # stands on it: left as code nothing reaches, a wekufe there is never
    # cast at all.
    test "is cast: every test is credited with what ran at start", %{reni: reni} do
      done = reply(run(reni, "started", ["baseline"]), "baseline_done")

      by_line =
        for %{"file" => "lib/started/counter.ex", "line" => line, "tests" => tests} <-
              done["coverage"],
            into: %{},
            do: {line, tests}

      # `start_link/1` and the body of `init/1`.
      assert by_line[4] == Enum.sort(@suite)
      assert by_line[15] == Enum.sort(@suite)

      # A line a test reaches by itself is left to the tests that reach it.
      assert by_line[9] == ["test/counter_test.exs:12"]

      start = Enum.find(done["coverage"], &(&1["file"] == "lib/started/application.ex"))
      assert start["tests"] == Enum.sort(@suite)
    end

    # What `test_helper.exs` set up after the first start is gone after a
    # second, and a test that fails for that would be a kill nobody made.
    test "is not judged where a restart alone fails the tests", %{reni: reni} do
      lines =
        summon(reni, "helped", [
          request("prepare", 2),
          request("baseline", 3),
          cast(
            "helped",
            "lib/helped/flags.ex",
            ["test/flags_test.exs:4", "test/flags_test.exs:8"],
            "at-start",
            4,
            {"limit: ", "10"},
            "11",
            "Helped.Flags.start_link/1"
          )
        ])

      done = by_wekufe(lines)["at-start"]
      assert done["outcome"] == "no_coverage"
      assert done["message"] =~ "could not be tried with the application started again"
      assert done["message"] =~ "test/flags_test.exs:4 fails"
    end

    # Code behind something filled the first time it is asked for has run
    # by now too, and no restart runs it again.
    test "is said not to have run where nothing starts it again", %{reni: reni} do
      lines =
        summon(reni, "lazy", [
          request("prepare", 2),
          request("baseline", 3),
          cast(
            "lazy",
            "lib/lazy.ex",
            ["test/lazy_test.exs:4", "test/lazy_test.exs:8"],
            "once",
            4,
            {"put(@key, 2)\n    ", "2"},
            "3",
            "Lazy.learn/0"
          )
        ])

      done = by_wekufe(lines)["once"]
      assert done["outcome"] == "no_coverage"
      assert done["message"] =~ "no test ran `Lazy.learn/0` while the wekufe was in it"
    end
  end

  defp by_wekufe(lines) do
    for line <- lines,
        {:ok, d} <- [Kalku.Json.decode(line)],
        d["wekufe"],
        into: %{},
        do: {d["wekufe"], d}
  end

  defp cast(fixture, file, tests, wekufe, id, {lead, target}, replacement, enclosing) do
    source = File.read!(Path.join(project(fixture), file))
    {at, _} = :binary.match(source, lead <> target)
    from = at + byte_size(lead)

    Kalku.Json.encode(%{
      "type" => "cast",
      "id" => id,
      "wekufe" => wekufe,
      "site" => %{
        "site_id" => wekufe,
        "file" => file,
        "enclosing" => enclosing,
        "span" => %{
          "start" => %{"line" => 1, "col" => 1, "byte" => from},
          "end" => %{"line" => 1, "col" => 1, "byte" => from + byte_size(target)}
        },
        "spell" => "literal",
        "replacement" => replacement,
        "reload" => "module"
      },
      "tests" => tests
    })
  end
end
