defmodule Attached.MixProject do
  use Mix.Project

  # A project whose application, as it starts, registers handlers that
  # belong to other applications: telemetry's and the logger's. Those
  # outlive the project's own application, so starting it a second time
  # finds them already there.
  def project do
    [
      app: :attached,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}, {:telemetry, "~> 1.0"}]
    ]
  end

  def application, do: [extra_applications: [:logger], mod: {Attached.Application, []}]
end
