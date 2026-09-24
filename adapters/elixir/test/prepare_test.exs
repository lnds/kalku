defmodule Kalku.PrepareTest do
  use ExUnit.Case, async: false

  alias Kalku.Prepare

  @fixture Path.expand("../fixtures/projects/green", __DIR__)

  describe "the reni is checked, not assumed" do
    test "a build path outside the reni refuses to compile anything" do
      {:error, code, message} = Prepare.run("/tmp/a-reni-this-build-is-not-in")

      assert code == "reni_not_isolated"
      assert message =~ Path.expand(Prepare.build_path())
      assert message =~ "MIX_BUILD_PATH"
    end

    test "a kalku summoned with no reni refuses too" do
      assert {:error, "reni_not_isolated", message} = Prepare.run(nil)
      assert message =~ "no reni"
    end

    # The one case that must pass: this suite's own build path is inside
    # the directory that contains it.
    test "a build path inside the reni is accepted" do
      reni = Path.dirname(Path.expand(Prepare.build_path()))
      assert {:ok, %{modules: modules}} = Prepare.run(reni)
      assert modules > 0
    end
  end

  describe "against a real project" do
    @describetag :subprocess

    setup do
      reni = Path.join(System.tmp_dir!(), "kalku-prepare-#{System.unique_integer([:positive])}")
      on_exit(fn -> File.rm_rf!(reni) end)
      {:ok, reni: reni}
    end

    test "prepare compiles into the reni and leaves the project's tree as it found it", %{
      reni: reni
    } do
      refute File.exists?(Path.join(@fixture, "_build"))

      lines = serve(reni, [hello(reni), ~s({"type":"prepare","id":2}), shutdown(3)])

      assert %{"type" => "prepared", "modules" => modules, "duration_ms" => ms} =
               reply(lines, "prepared")

      assert modules > 0
      assert ms >= 0

      assert File.exists?(Path.join(reni, "build/lib/green/ebin/Elixir.Green.beam"))
      refute File.exists?(Path.join(@fixture, "_build"))
    end

    # The bug this test exists for: mix writes to stdout, and stdout is the
    # protocol. A single line of compiler chatter makes the kaikai side
    # banish the worker for writing nonsense it never meant to write.
    test "nothing but protocol reaches stdout", %{reni: reni} do
      lines = serve(reni, [hello(reni), ~s({"type":"prepare","id":2}), shutdown(3)])

      for line <- lines do
        assert {:ok, _} = JSON.decode(line), "not a protocol line on stdout: #{inspect(line)}"
      end

      assert length(lines) == 3
    end

    test "a project that does not compile is a fatal error naming the file", %{reni: reni} do
      broken = copy_of_fixture()

      File.write!(
        Path.join(broken, "lib/green.ex"),
        "defmodule Green do\n  def oops(, do:\nend\n"
      )

      lines = serve(reni, [hello(reni), ~s({"type":"prepare","id":2}), shutdown(3)], broken)
      error = reply(lines, "error")

      assert error["code"] == "prepare_failed"
      assert error["fatal"] == true
      assert error["message"] =~ "green.ex"
    end
  end

  # A copy outside the repo, so a project that does not compile is not this
  # one. Its path dependency is rewritten absolute, since a relative one
  # only resolves from where the fixture lives.
  defp copy_of_fixture do
    dir = Path.join(System.tmp_dir!(), "kalku-broken-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf!(dir) end)
    File.cp_r!(@fixture, dir)
    adapter = Path.expand("..", __DIR__)

    Path.join(dir, "mix.exs")
    |> File.write!("""
    defmodule Green.MixProject do
      use Mix.Project

      def project do
        [
          app: :green,
          version: "0.1.0",
          elixir: "~> 1.18",
          deps: [{:kalku_elixir, path: "#{adapter}"}]
        ]
      end

      def application, do: [extra_applications: [:logger]]
    end
    """)

    dir
  end

  test "the summoner refuses to run without a reni to build into" do
    {out, status} =
      System.cmd("sh", ["-c", "#{summoner()} 2>&1"], cd: @fixture, env: [{"MIX_BUILD_PATH", ""}])

    assert status == 2
    assert out =~ "MIX_BUILD_PATH"
  end

  defp hello(reni) do
    JSON.encode!(%{
      "type" => "hello",
      "id" => 1,
      "protocol" => 1,
      "root" => ".",
      "reni" => reni,
      "worker" => 0,
      "inline_limit_bytes" => 65_536,
      "env" => %{}
    })
  end

  defp shutdown(id), do: ~s({"type":"shutdown","id":#{id}})

  # Drives a real kalku over a real pipe: the only way to see what lands on
  # stdout, which is what the protocol rides on.
  defp serve(reni, requests, cwd \\ @fixture) do
    input = Enum.map_join(requests, "", &(&1 <> "\n"))
    script = Path.join(System.tmp_dir!(), "kalku-in-#{System.unique_integer([:positive])}")
    File.write!(script, input)
    on_exit(fn -> File.rm_rf!(script) end)

    # Summoned the one supported way, so these tests exercise the contract
    # rather than a second copy of it.
    {out, _status} =
      System.cmd("sh", ["-c", "#{summoner()} < #{script} 2>/dev/null"],
        cd: cwd,
        env: [{"MIX_BUILD_PATH", Path.join(reni, "build")}]
      )

    out |> String.split("\n", trim: true)
  end

  defp summoner, do: Path.expand("../bin/kalku-elixir", __DIR__)

  defp reply(lines, type) do
    lines
    |> Enum.map(&JSON.decode/1)
    |> Enum.find_value(fn
      {:ok, %{"type" => ^type} = body} -> body
      _ -> nil
    end) || flunk("no `#{type}` in:\n#{Enum.join(lines, "\n")}")
  end
end
