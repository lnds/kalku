defmodule Mix.Tasks.Kalku.Serve do
  @shortdoc "Runs the kalku protocol loop on stdio"
  @moduledoc """
  Runs the Elixir kalku: reads protocol requests from stdin and writes
  replies to stdout (docs/protocol.md). Compiler output is silenced so
  stdout carries protocol lines only.
  """

  use Mix.Task

  @impl true
  def run(_args) do
    Mix.shell(Mix.Shell.Quiet)
    Mix.Task.run("compile")
    Kalku.Loop.run()
  end
end
