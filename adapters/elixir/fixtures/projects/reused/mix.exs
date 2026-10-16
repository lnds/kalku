defmodule Reused.MixProject do
  use Mix.Project

  # Test modules that reach different parts of the project, for a baseline
  # that runs only what changed. It depends on the kalku by path, the way a
  # user's project would.
  def project do
    [
      app: :reused,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
