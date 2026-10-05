defmodule Kalku.KalkuCase do
  @moduledoc """
  Driving a real kalku over a real pipe.

  Everything here goes through `bin/kalku-elixir` and a pipe, because that
  is the only place some of this is visible: what lands on stdout, what a
  build writes on the way up, and whether a project that will not compile
  takes the kalku with it. A kalku exercised by calling its functions
  directly would pass all of it.
  """

  import ExUnit.Assertions

  @projects Path.expand("../../fixtures/projects", __DIR__)
  @summoner Path.expand("../../bin/kalku-elixir", __DIR__)

  @doc "A reni of its own for one test, removed afterwards."
  def a_reni(_context) do
    reni = Path.join(System.tmp_dir!(), "kalku-#{System.unique_integer([:positive])}")
    ExUnit.Callbacks.on_exit(fn -> File.rm_rf!(reni) end)
    {:ok, reni: reni}
  end

  @doc "Where a fixture project lives."
  def project(name), do: Path.join(@projects, name)

  @doc """
  Summons a kalku against a fixture, says hello and prepares, then asks
  for each named request in turn before saying goodbye.
  """
  def run(reni, fixture, types) do
    asked =
      types
      |> Enum.with_index(3)
      |> Enum.map(fn {type, id} -> request(type, id) end)

    summon(reni, fixture, [request("prepare", 2) | asked], next_id(types))
  end

  @doc "Summons a kalku and feeds it exactly these lines, after `hello`."
  def summon(reni, fixture, requests, shutdown_id \\ 99) do
    lines = [hello(reni) | requests] ++ [request("shutdown", shutdown_id)]
    drive(reni, project(fixture), lines)
  end

  @doc "Where the summoner lives, for a test that runs it by hand."
  def summoner, do: @summoner

  @doc "Drives a kalku in a directory that is not a fixture — a copy, say."
  def drive_in(dir, reni, lines), do: drive(reni, dir, lines)

  @doc "The body of the first reply of this type, or a failure saying what came instead."
  def reply(lines, type) do
    lines
    |> Enum.map(&Kalku.Json.decode/1)
    |> Enum.find_value(fn
      {:ok, %{"type" => ^type} = body} -> body
      _ -> nil
    end) ||
      flunk("""
      no `#{type}` on stdout:
      #{Enum.join(lines, "\n")}

      stderr was:
      #{Process.get(:last_stderr, "(not captured)")}
      """)
  end

  @doc "A `hello` naming this reni."
  def hello(reni, root \\ ".") do
    Kalku.Json.encode(%{
      "type" => "hello",
      "id" => 1,
      "protocol" => 1,
      "root" => root,
      "reni" => reni,
      "worker" => 0,
      "inline_limit_bytes" => 65_536,
      "env" => %{}
    })
  end

  @doc "A request of this type carrying no fields."
  def request(type, id), do: ~s({"type":"#{type}","id":#{id}})

  # ---- the pipe -----------------------------------------------------

  defp drive(reni, cwd, lines) do
    input = write_temp(Enum.map_join(lines, "", &(&1 <> "\n")))
    errors = write_temp("")

    {out, status} =
      System.cmd("sh", ["-c", "#{@summoner} < #{input} 2> #{errors}"],
        cd: cwd,
        env: [{"MIX_BUILD_PATH", Path.join(reni, "build")}]
      )

    Process.put(:last_stderr, File.read!(errors))
    said = String.split(out, "\n", trim: true)

    if said == [] do
      flunk("""
      the kalku wrote nothing on stdout and exited #{status}. Its stderr:

      #{File.read!(errors)}
      """)
    end

    said
  end

  defp write_temp(contents) do
    path = Path.join(System.tmp_dir!(), "kalku-#{System.unique_integer([:positive])}")
    File.write!(path, contents)
    ExUnit.Callbacks.on_exit(fn -> File.rm_rf!(path) end)
    path
  end

  defp next_id(types), do: 3 + length(types)
end
