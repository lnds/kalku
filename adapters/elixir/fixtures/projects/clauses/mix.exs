defmodule Clauses.MixProject do
  use Mix.Project

  # A project where every comparison has a test on each side of it, and the
  # test of the boundary goes through another clause, or through none.
  def project do
    [
      app: :clauses,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
