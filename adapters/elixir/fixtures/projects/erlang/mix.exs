defmodule Erlang.MixProject do
  use Mix.Project

  # A project with a dependency written in Erlang and built by rebar3,
  # which keeps a cache of its own beside the sources it compiles.
  def project do
    [
      app: :erlang,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [
        {:kalku_elixir, path: "../../.."},
        {:tally, path: "vendor/tally", manager: :rebar3}
      ]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
