defmodule Kalku.MixProject do
  use Mix.Project

  @version "0.0.0"

  def project do
    [
      app: :kalku_elixir,
      version: @version,
      elixir: "~> 1.18",
      start_permanent: false,
      deps: [],
      elixirc_paths: elixirc_paths(Mix.env()),
      test_ignore_filters: [~r{^test/fixtures/}]
    ]
  end

  # Test-only helpers compile with the suite, never with the kalku a user
  # runs inside their project.
  defp elixirc_paths(:test), do: ["lib", "test/support"]
  defp elixirc_paths(_), do: ["lib"]

  # The kalku runs inside the user's project runtime, so it brings no
  # runtime dependencies that could conflict with theirs.
  def application, do: [extra_applications: [:crypto]]
end
