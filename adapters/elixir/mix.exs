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
      elixirc_paths: ["lib"],
      test_ignore_filters: [~r{^test/fixtures/}]
    ]
  end

  # The kalku runs inside the user's project runtime, so it brings no
  # runtime dependencies that could conflict with theirs.
  def application, do: [extra_applications: [:crypto]]
end
