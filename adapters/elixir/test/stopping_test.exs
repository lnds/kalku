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
          cast(4, {"done ", ">"}, ">="),
          cast(5, {"done > ", "10"}, "100")
        ])

      assert reply(lines, "baseline_done")["status"] == "green"

      [stopped, quiet] =
        lines
        |> Enum.map(&Kalku.Json.decode!/1)
        |> Enum.filter(&(&1["type"] == "cast_done"))

      # Not `killed`: no test said so. Not `survived`: none got to say.
      assert stopped["outcome"] == "crashed"
      assert stopped["message"] =~ "stopped being run before they ended"

      assert quiet["outcome"] == "survived"
    end
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

  defp cast(id, {lead, target}, replacement) do
    file = "lib/stopping.ex"
    source = File.read!(Path.join(project("stopping"), file))
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
      "tests" => ["test/stopping_test.exs:4"]
    })
  end
end
