defmodule Mix.Tasks.Partitioned.Mark do
  @moduledoc false
  use Mix.Task

  # Stands in for `ecto.create`: it says which database it would make, on
  # stdout as a task would, and leaves a mark in the build saying so.
  def run(_args) do
    partition = System.get_env("MIX_TEST_PARTITION", "")
    IO.puts("making the database for partition #{partition}")
    File.write!(Path.join(Mix.Project.build_path(), "marked"), partition)
  end
end

defmodule Partitioned.MixProject do
  use Mix.Project

  # A project whose `test` alias makes a database per partition before the
  # suite runs, the way an Ecto project's does.
  def project do
    [
      app: :partitioned,
      version: "0.1.0",
      elixir: "~> 1.16",
      aliases: [test: ["partitioned.mark", &noted/1, "test"]],
      deps: [{:kalku_elixir, path: "../../.."}]
    ]
  end

  def application, do: [extra_applications: [:logger]]

  defp noted(_args) do
    File.write!(Path.join(Mix.Project.build_path(), "noted"), "yes")
  end
end
