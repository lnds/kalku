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

  @impl true
  def run(_args) do
    Mix.shell(Mix.Shell.Quiet)
    Kalku.Loop.run()
  end
end
