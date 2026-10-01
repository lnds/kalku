defmodule Mocked.MixProject do
  use Mix.Project

  # A project whose suite mocks one of its own modules, which is what
  # every Elixir project of any size does. A mocking library replaces the
  # module, `:cover` loses the one it instrumented, and per-test coverage
  # stops being a measurement.
  def project do
    [
      app: :mocked,
      version: "0.1.0",
      elixir: "~> 1.18",
      deps: [{:kalku_elixir, path: "../../.."}, {:mimic, "~> 1.7", only: :test}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
