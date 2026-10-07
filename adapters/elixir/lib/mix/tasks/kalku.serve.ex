defmodule Mix.Tasks.Kalku.Serve do
  @shortdoc "Runs the kalku protocol loop on stdio"
  @moduledoc """
  Runs the Elixir kalku: reads protocol requests from stdin and writes
  replies to stdout (docs/protocol.md).

  Starting does not compile. stdout is the protocol, and mix writes what
  it compiles to stdout, so a kalku that compiled on the way up would
  corrupt its own first message — and a project that failed to compile
  would take the kalku with it before it could say why. Compiling is what
  `prepare` is for, and it reports what happened as a protocol message.
  """

  use Mix.Task

  @requirements []

  # The oldest Elixir this kalku is tested on. Below it nothing is promised,
  # and a loop that dies on the way up reaches the other side as a closed
  # pipe that tells nobody the version is the reason.
  @elixir_floor "1.16.0"

  @impl true
  def run(_args) do
    case too_old() do
      nil ->
        Mix.shell(Mix.Shell.Quiet)
        load_all()
        Kalku.Loop.run()

      said ->
        IO.puts(:stderr, said)
        exit({:shutdown, 1})
    end
  end

  # Mix takes off the code path whatever the project does not depend on,
  # when it compiles, and a kalku summoned from outside the project is
  # exactly that. A module already loaded stays loaded, so all of them
  # are loaded before anything is compiled.
  defp load_all do
    for beam <- Path.wildcard(Path.join(Path.dirname(:code.which(__MODULE__)), "*.beam")) do
      beam |> Path.basename(".beam") |> String.to_atom() |> Code.ensure_loaded()
    end
  end

  defp too_old do
    running = System.version()

    if Version.match?(running, ">= #{@elixir_floor}") do
      nil
    else
      "kalku_elixir needs Elixir #{@elixir_floor} or newer; " <>
        "this project runs #{running}. Upgrade Elixir, or pin kalku_elixir out of this project."
    end
  end
end
