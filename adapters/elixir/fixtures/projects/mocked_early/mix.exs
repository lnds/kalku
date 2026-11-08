defmodule MockedEarly.MixProject do
  use Mix.Project

  # A suite that mocks with a Mimic of the 1.8 line, which compiles the mock
  # it puts under a module's name in its own file, while `:cover` keeps
  # counting the replaced module's lines under that name.
  def project do
    [
      app: :mocked_early,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: [{:kalku_elixir, path: "../../.."}, {:mimic, "1.8.2", only: :test}]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
