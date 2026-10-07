defmodule Kalku.OutsideTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  # A project that has never heard of kalku: nothing in its `mix.exs` names
  # it. Trying kalku on one used to mean adding the dependency, and with it
  # a changed `mix.lock` — two versioned files, to find out what the tool
  # says. The kalku is built into the reni instead and the project is left
  # exactly as it was, down to the files it ignores.
  describe "a project that does not depend on the kalku" do
    setup :a_reni

    test "is measured, a wekufe is cast, and nothing in it changes", %{reni: reni} do
      dir = a_stranger()
      before = everything_in(dir)

      lines =
        drive_in(dir, reni, [
          hello(reni),
          request("prepare", 2),
          request("baseline", 3),
          cast(
            dir,
            4,
            "lib/mocked/fee.ex",
            {"amount, ", "10"},
            "20",
            "test/late_copy_test.exs:15"
          ),
          request("shutdown", 5)
        ])

      # Its dependencies come from Hex, which is what a build that had lost
      # sight of Hex would stop at.
      assert reply(lines, "prepared")["modules"] > 0

      done = reply(lines, "baseline_done")
      assert done["status"] == "green"
      assert length(done["tests"]) == 5
      assert Enum.any?(done["coverage"], &(&1["file"] == "lib/mocked/fee.ex"))

      # A cast compiles again, which is when the kalku would be lost.
      assert reply(lines, "cast_done")["outcome"] == "killed"

      assert everything_in(dir) == before
    end
  end

  # The `mocked` fixture without the line that names the kalku, under git
  # so that what changes can be asked of something that knows.
  defp a_stranger do
    dir = Path.join(System.tmp_dir!(), "kalku-stranger-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf!(dir) end)
    File.cp_r!(project("mocked"), dir)
    File.rm_rf!(Path.join(dir, "_build"))

    mix = Path.join(dir, "mix.exs")
    was = File.read!(mix)
    now = String.replace(was, ~s({:kalku_elixir, path: "../../.."}, ), "")
    assert now != was
    refute now =~ "kalku"
    File.write!(mix, now)

    git(dir, ["init", "-q"])
    git(dir, ["add", "-A"])

    git(dir, [
      "-c",
      "user.name=kalku",
      "-c",
      "user.email=kalku@example.com",
      "commit",
      "-qm",
      "it"
    ])

    dir
  end

  # Tracked, untracked and ignored: `deps` and `_build` are ignored, and a
  # run that wrote into them would otherwise go unseen.
  defp everything_in(dir) do
    {git(dir, ["status", "--porcelain", "--ignored"]), Enum.sort(File.ls!(dir)),
     File.read!(Path.join(dir, "mix.exs")), File.read!(Path.join(dir, "mix.lock"))}
  end

  defp git(dir, args) do
    {out, 0} = System.cmd("git", args, cd: dir, stderr_to_stdout: true)
    out
  end

  defp cast(dir, id, file, {lead, target}, replacement, test) do
    source = File.read!(Path.join(dir, file))
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
          "start" => %{"line" => 3, "col" => 1, "byte" => from},
          "end" => %{"line" => 3, "col" => 1, "byte" => from + byte_size(target)}
        },
        "spell" => "literal",
        "replacement" => replacement,
        "reload" => "module"
      },
      "tests" => [test]
    })
  end
end
