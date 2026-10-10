defmodule Spoken.MixProject do
  use Mix.Project

  # A project written in a language ASCII does not hold: its literals and
  # the names of its tests carry accents and emoji.
  def project do
    [
      app: :spoken,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
