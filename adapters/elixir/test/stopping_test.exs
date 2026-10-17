defmodule Kalku.StoppingTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  # ExUnit is an application like any other, and code under test can stop
  # it: a project that shuts services down, reached from a test or made to
  # by a wekufe. The run of the tests then ends without ExUnit saying it
  # ended, or does not return at all, and the kalku went with it — a pipe
  # closed, and a stack trace on stderr for whoever thought to look.
  describe "a suite that stops what runs it" do
    setup :a_reni

    test "is a baseline that failed, and says so", %{reni: reni} do
      lines =
        drive_in(stopped_by_its_test(), reni, [
          hello(reni),
          request("prepare", 2),
          request("baseline", 3),
          request("shutdown", 4)
        ])

      failed = reply(lines, "error")
      assert failed["id"] == 3
      assert failed["code"] == "baseline_failed"
      assert failed["fatal"] == true
      assert failed["message"] =~ "stopped being run before it ended"
      assert reply(lines, "bye")["id"] == 4
    end

    test "under a wekufe is a cast that judged nothing, and the next is judged", %{reni: reni} do
      lines =
        summon(reni, "stopping", [
          request("prepare", 2),
          request("baseline", 3),
          cast("stopping", 4, {"done ", ">"}, ">="),
          request("reset", 5),
          cast("stopping", 6, {"done > ", "10"}, "100")
        ])

      assert reply(lines, "baseline_done")["status"] == "green"

      [stopped, quiet] = casts_done(lines)

      # Not `killed`: no test said so. Not `survived`: none got to say.
      assert stopped["outcome"] == "crashed"
      assert stopped["message"] =~ "stopped being run before they ended"

      # A run that stopped halfway leaves ExUnit refusing the next one until
      # it is started again: a runtime left changed.
      assert stopped["dirty"] == true
      assert reply(lines, "reset_done")["clean"] == true

      assert quiet["outcome"] == "survived"
      assert quiet["dirty"] == false
    end
  end

  # ExUnit judges every cast after this one, so a wekufe that changes how it
  # runs would be blamed on each of them: `crashed`, cast after cast.
  describe "a wekufe that changes ExUnit's configuration" do
    setup :a_reni

    test "leaves the runtime dirty, and a reset puts it back for the next cast",
         %{reni: reni} do
      lines =
        summon(reni, "forgetting", [
          request("prepare", 2),
          request("baseline", 3),
          cast("forgetting", 4, {"done ", ">"}, ">="),
          request("reset", 5),
          cast("forgetting", 6, {"done > ", "10"}, "100")
        ])

      assert reply(lines, "baseline_done")["status"] == "green"

      [forgot, next] = casts_done(lines)
      assert forgot["dirty"] == true
      assert reply(lines, "reset_done")["clean"] == true

      assert next["outcome"] == "survived"
      assert next["dirty"] == false
    end
  end

  defp casts_done(lines) do
    lines
    |> Enum.map(&Kalku.Json.decode!/1)
    |> Enum.filter(&(&1["type"] == "cast_done"))
  end

  # The fixture, with its test asking for one more than is left to do.
  defp stopped_by_its_test do
    dir = Path.join(System.tmp_dir!(), "kalku-stopping-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf!(dir) end)
    File.cp_r!(project("stopping"), dir)
    File.rm_rf!(Path.join(dir, "_build"))

    rewrite(Path.join(dir, "mix.exs"), ~s({:kalku_elixir, path: "../../.."}), "")
    rewrite(Path.join(dir, "test/stopping_test.exs"), "settle(10)", "settle(11)")
    dir
  end

  defp rewrite(path, was, now) do
    text = File.read!(path)
    true = String.contains?(text, was)
    File.write!(path, String.replace(text, was, now))
  end

  defp cast(fixture, id, {lead, target}, replacement) do
    file = "lib/#{fixture}.ex"
    source = File.read!(Path.join(project(fixture), file))
    {at, _} = :binary.match(source, lead <> target)
    from = at + byte_size(lead)

    Kalku.Json.encode(%{
      "type" => "cast",
      "id" => id,
      "wekufe" => "w#{id}",
      "site" => %{
        "site_id" => "w#{id}",
        "file" => file,
        "span" => %{
          "start" => %{"line" => 7, "col" => 1, "byte" => from},
          "end" => %{"line" => 7, "col" => 1, "byte" => from + byte_size(target)}
        },
        "spell" => "compare",
        "replacement" => replacement,
        "reload" => "module"
      },
      "tests" => ["test/#{fixture}_test.exs:4"]
    })
  end
end
