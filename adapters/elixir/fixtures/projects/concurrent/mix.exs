defmodule Concurrent.MixProject do
  use Mix.Project

  # A project whose interesting decisions are about time and failure, for
  # the spells that measure those.
  def project do
    [
      app: :concurrent,
      version: "0.1.0",
      elixir: "~> 1.18",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
