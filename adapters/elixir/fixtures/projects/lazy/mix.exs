defmodule Lazy.MixProject do
  use Mix.Project

  # A project that sets something up the first time it is asked, and never
  # again while the runtime lives: the suite reaches that code once, and no
  # test run afterwards on its own does.
  def project do
    [
      app: :lazy,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
