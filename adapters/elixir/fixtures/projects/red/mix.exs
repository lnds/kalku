defmodule Red.MixProject do
  use Mix.Project

  # A small project for the kalku to prepare and measure. It depends on
  # the kalku by path, the way a user's project would.
  def project do
    [
      app: :red,
      version: "0.1.0",
      elixir: "~> 1.18",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
