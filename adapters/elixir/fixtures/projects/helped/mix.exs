defmodule Helped.MixProject do
  use Mix.Project

  # A project whose tests stand on something `test/test_helper.exs` sets up
  # once the application has started: started again, the application has
  # lost it, and the tests fail whatever code is loaded.
  def project do
    [
      app: :helped,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger], mod: {Helped.Application, []}]
end
