defmodule Stopping.MixProject do
  use Mix.Project

  # A project that can stop what runs its tests: one wrong comparison away
  # from it in the code, and one argument away from it in the test.
  def project do
    [
      app: :stopping,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
