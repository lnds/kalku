defmodule Started.MixProject do
  use Mix.Project

  # A project whose application starts a process: what that process is
  # started with is code that runs once, before any test does.
  def project do
    [
      app: :started,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger], mod: {Started.Application, []}]
end
