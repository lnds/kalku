defmodule Loud.MixProject do
  use Mix.Project

  # A project whose application logs while it starts, which is before a
  # kalku has had a chance to look at the handlers that start installed.
  def project do
    [
      app: :loud,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger], mod: {Loud.Application, []}]
end
