defmodule Kalku.PrepareTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  alias Kalku.Prepare

  describe "the reni is checked, not assumed" do
    test "a build path outside the reni refuses to compile anything" do
      {:error, code, message} = Prepare.run(".", "/tmp/a-reni-this-build-is-not-in")

      assert code == "reni_not_isolated"
      assert message =~ Path.expand(Prepare.build_path())
      assert message =~ "MIX_BUILD_PATH"
    end

    test "a kalku summoned with no reni refuses too" do
      assert {:error, "reni_not_isolated", message} = Prepare.run(".", nil)
      assert message =~ "no reni"
    end

    # The one case that must pass: this suite's own build path is inside
    # the directory that contains it.
    test "a build path inside the reni is accepted" do
      reni = Path.dirname(Path.expand(Prepare.build_path()))
      assert {:ok, %{modules: modules}} = Prepare.run(".", reni)
      assert modules > 0
    end

    # A reni reached through a symlink is the same reni: on macOS every
    # temporary directory is, because `/var` is a link to `private/var`,
    # and a check that compares the names rather than the directories
    # refuses a kalku that was summoned correctly.
    test "a reni named through a symlink is still the reni" do
      reni = Path.dirname(Path.expand(Prepare.build_path()))
      link = Path.join(System.tmp_dir!(), "kalku-reni-link-#{System.unique_integer([:positive])}")
      File.rm(link)
      :ok = File.ln_s(reni, link)
      on_exit(fn -> File.rm(link) end)

      assert {:ok, _} = Prepare.run(".", link)
    end
  end

  describe "against a real project" do
    setup :a_reni

    test "prepare compiles into the reni and leaves the project's tree as it found it", %{
      reni: reni
    } do
      refute File.exists?(Path.join(project("green"), "_build"))

      done = reply(summon(reni, "green", [request("prepare", 2)]), "prepared")

      assert done["modules"] > 0
      assert done["duration_ms"] >= 0

      assert File.exists?(Path.join(reni, "build/lib/green/ebin/Elixir.Green.beam"))
      refute File.exists?(Path.join(project("green"), "_build"))
    end

    # The bug this test exists for: mix writes to stdout, and stdout is the
    # protocol. A single line of compiler chatter makes the kaikai side
    # banish the worker for writing nonsense it never meant to write.
    test "nothing but protocol reaches stdout", %{reni: reni} do
      lines = summon(reni, "green", [request("prepare", 2)])

      for line <- lines do
        assert {:ok, _} = Kalku.Json.decode(line),
               "not a protocol line on stdout: #{inspect(line)}"
      end

      assert length(lines) == 3
    end

    # Starting the project's applications installs the logger's handlers, on
    # stdout, and an application is free to log while it starts: before the
    # kalku can look at what was installed.
    test "an application that logs while it starts does not write on stdout", %{reni: reni} do
      lines = summon(reni, "loud", [request("prepare", 2)])

      for line <- lines do
        assert {:ok, _} = Kalku.Json.decode(line),
               "not a protocol line on stdout: #{inspect(line)}"
      end

      assert reply(lines, "prepared")["modules"] > 0
      assert Process.get(:last_stderr) =~ "loud: could not reach what it wanted"
    end

    # A runtime writes on stdout by default, from anywhere: a test, a process
    # of the application, the application as it starts.
    test "what the project prints does not reach stdout", %{reni: reni} do
      lines = summon(reni, "loud", [request("prepare", 2), request("baseline", 3)])

      for line <- lines do
        assert {:ok, _} = Kalku.Json.decode(line),
               "not a protocol line on stdout: #{inspect(line)}"
      end

      assert reply(lines, "baseline_done")["status"] == "green"

      for said <- ["starting", "a test printing", "the application printing"] do
        assert Process.get(:last_stderr) =~ "loud: " <> said
      end
    end

    test "a project that does not compile is a fatal error naming the file", %{reni: reni} do
      broken = broken_copy()
      lines = drive_in(broken, reni, [hello(reni), request("prepare", 2), request("shutdown", 3)])
      error = reply(lines, "error")

      assert error["code"] == "prepare_failed"
      assert error["fatal"] == true
      assert error["message"] =~ "green.ex"
    end

    test "the summoner refuses to run without a reni to build into" do
      assert {out, 2} =
               System.cmd("sh", ["-c", "#{summoner()} 2>&1"],
                 cd: project("green"),
                 env: [{"MIX_BUILD_PATH", ""}]
               )

      assert out =~ "MIX_BUILD_PATH"
    end
  end

  # A copy outside the repo, so a project that does not compile is not this
  # one. Its path dependency is rewritten absolute, since a relative one
  # only resolves from where the fixture lives.
  defp broken_copy do
    dir = Path.join(System.tmp_dir!(), "kalku-broken-#{System.unique_integer([:positive])}")
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

    File.write!(Path.join(dir, "lib/green.ex"), "defmodule Green do\n  def oops(, do:\nend\n")
    dir
  end
end
