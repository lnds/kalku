defmodule Kalku.MixProject do
  use Mix.Project

  @version "0.12.0"
  @source_url "https://github.com/lnds/kalku"

  def project do
    [
      app: :kalku_elixir,
      version: @version,
      elixir: "~> 1.16",
      start_permanent: false,
      deps: [],
      elixirc_paths: elixirc_paths(Mix.env()),
      test_ignore_filters: [~r{^test/fixtures/}],
      description: description(),
      package: package(),
      source_url: @source_url,
      docs: [main: "readme", extras: ["README.md"], source_ref: "v#{@version}"]
    ]
  end

  defp description do
    "The Elixir kalku: the worker that mutation-tests an Elixir project " <>
      "for kalku. It finds sites with Elixir's own parser, casts each " <>
      "wekufe into a warm BEAM, and runs only the tests that cover it."
  end

  # The package carries the kalku and the script that summons it, and
  # nothing from the suite that tests it: fixtures are whole Mix projects,
  # and they are not what a user installs.
  defp package do
    [
      licenses: ["MIT", "Apache-2.0"],
      files: ~w(lib bin mix.exs README.md LICENSE-MIT LICENSE-APACHE),
      links: %{
        "GitHub" => @source_url,
        "Protocol" => "#{@source_url}/blob/main/docs/protocol.md"
      }
    ]
  end

  # Test-only helpers compile with the suite, never with the kalku a user
  # runs inside their project.
  defp elixirc_paths(:test), do: ["lib", "test/support"]
  defp elixirc_paths(_), do: ["lib"]

  # The kalku runs inside the user's project runtime, so it brings no
  # runtime dependencies that could conflict with theirs.
  def application, do: [extra_applications: [:crypto, :tools]]
end
