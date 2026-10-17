defmodule Forgetting.MixProject do
  use Mix.Project

  # A project that can change what runs its tests: one wrong comparison
  # away from taking a key out of ExUnit's configuration.
  def project do
    [
      app: :forgetting,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
