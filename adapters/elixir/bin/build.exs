# Compiles the kalku into a directory of its own, for a project that does
# not depend on it. Run with the project's own Elixir, from the project:
# a module compiled by another Elixir is not one this runtime can load.
#
#   elixir bin/build.exs <where the kalku is> <where its modules go>
[home, ebin] = System.argv()

sources = Path.wildcard(Path.join(home, "lib/**/*.ex")) |> Enum.sort()

# What was built and with what, so the next summoning builds nothing.
stamp = Path.join(ebin, "built-with")

built_with =
  [System.version(), :erlang.system_info(:otp_release) | Enum.map(sources, &File.read!/1)]
  |> :erlang.md5()
  |> Base.encode16()

if File.read(stamp) != {:ok, built_with} do
  File.rm_rf!(ebin)
  File.mkdir_p!(ebin)
  Mix.start()

  # The kalku reads its own version from its own `mix.exs` as it compiles.
  compiled =
    Mix.Project.in_project(:kalku_elixir, home, fn _ ->
      Kernel.ParallelCompiler.compile_to_path(sources, ebin, return_diagnostics: true)
    end)

  case compiled do
    {:ok, _modules, _diagnostics} ->
      File.write!(stamp, built_with)

    {:error, errors, _diagnostics} ->
      IO.puts(:stderr, "kalku-elixir: the kalku did not compile with this Elixir")
      IO.inspect(errors, label: "kalku-elixir", device: :stderr)
      System.halt(1)
  end
end
