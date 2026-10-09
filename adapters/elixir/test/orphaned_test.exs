defmodule Kalku.OrphanedTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  alias Kalku.Orphaned

  describe "what a runtime started" do
    test "is its children and theirs, whatever group they are in" do
      listed = """
          1     0
        100     1
        200   100
        201   100
        300   200
        400     1
        401   400
      """

      assert Enum.sort(Orphaned.descendants(listed, "100")) == ["200", "201", "300"]
      assert Orphaned.descendants(listed, "300") == []
    end
  end

  describe "a kalku whose run is gone" do
    setup :a_reni

    # A run that is killed asks nothing of its kalku: the input just ends,
    # in the middle of a suite that has minutes left to run.
    test "ends with what it started, in the middle of the suite", %{reni: reni} do
      slow = slow_copy()
      asked = Enum.join([hello(reni), request("prepare", 2), request("baseline", 3)], "\n")
      said = Path.join(slow, "child.pid")

      # The input ends once the test has started its program, not before.
      {out, _} =
        System.cmd(
          "sh",
          [
            "-c",
            "(echo '#{asked}'; until [ -s #{said} ]; do sleep 0.1; done) | #{summoner()} 2>/dev/null"
          ],
          cd: slow,
          env: [{"MIX_BUILD_PATH", Path.join(reni, "build")}]
        )

      lines = String.split(out, "\n", trim: true)
      assert reply(lines, "prepared")
      refute Enum.any?(lines, &(&1 =~ "baseline_done"))

      child = said |> File.read!() |> String.trim()
      assert {_, 1} = System.cmd("kill", ["-0", child], stderr_to_stdout: true)
    end

    # Input that ends after `shutdown` is a driver that wrote everything it
    # had to say and closed: every request is still answered.
    test "is not one that was told to leave", %{reni: reni} do
      lines = summon(reni, "green", [request("prepare", 2), request("baseline", 3)])

      assert reply(lines, "baseline_done")["status"] == "green"
      assert reply(lines, "bye")
    end
  end

  # The green project with one more test: it starts a program, says which,
  # and then takes longer than anybody waits.
  defp slow_copy do
    dir = Path.join(System.tmp_dir!(), "kalku-slow-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf!(dir) end)
    File.cp_r!(project("green"), dir)
    adapter = Path.expand("..", __DIR__)

    File.write!(Path.join(dir, "mix.exs"), """
    defmodule Green.MixProject do
      use Mix.Project

      def project do
        [
          app: :green,
          version: "0.1.0",
          elixir: "~> 1.16",
          deps: [{:kalku_elixir, path: "#{adapter}"}]
        ]
      end

      def application, do: [extra_applications: [:logger]]
    end
    """)

    File.write!(Path.join(dir, "test/slow_test.exs"), """
    defmodule SlowTest do
      use ExUnit.Case

      test "starts a program and waits" do
        port = Port.open({:spawn_executable, "/bin/sleep"}, args: ["600"])
        {:os_pid, pid} = Port.info(port, :os_pid)
        File.write!("#{dir}/child.pid", Integer.to_string(pid))
        Process.sleep(600_000)
      end
    end
    """)

    dir
  end
end
