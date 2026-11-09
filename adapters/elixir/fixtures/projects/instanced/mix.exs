defmodule Instanced.MixProject do
  use Mix.Project

  # A project whose application starts a process that names an ETS table
  # after itself, as an HTTP client profile does. Each process that takes
  # its place names a new table, so the name changes with every restart.
  def project do
    [
      app: :instanced,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [mod: {Instanced.Application, []}]
end
