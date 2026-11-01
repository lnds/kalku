defmodule Excluding.MixProject do
  use Mix.Project

  # A project whose `test_helper.exs` leaves some of its tests out, the way
  # a suite leaves out what its Elixir version does not support.
  def project do
    [
      app: :excluding,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
