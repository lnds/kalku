defmodule Kalku.Baseline.Fingerprint do
  @moduledoc """
  What the tests of one test module run, as one string that changes when
  any of it does.

  The unit is the test module, not the test: `setup_all` and whatever a
  module's tests share are run once for all of them, so a module is run
  whole or not at all.

  A test module runs its own file and what that file names, and what those
  name in turn: the files the compiler says each file depends on, at
  compile time or at run time, all the way down. Macros and module
  attributes are stitched into the module that uses them, and the compiler
  records that as a dependency, so following them reaches the file the
  expansion came from. Each file goes in by its name and what is in it.
  What is not the project's own goes in by `mix.lock` and the versions of
  Elixir and OTP. What a test can reach without the compiler saying so —
  the configuration, `priv/`, the Erlang sources under `src/`, and every
  file under `test/` that is not a test file — goes in for every module.

  A dependency the compiler cannot see — a module named in the
  configuration and called through a variable, a protocol dispatching to an
  implementation — would be missed. So a module is given its fingerprint
  only when every file its tests were seen to execute is one the
  fingerprint covers; one whose tests reached further is not fingerprinted,
  and always runs. Given the same files, a test takes the same way through
  them, so the check made when the fingerprint was given holds whenever it
  matches again.

  What a test reads from anywhere else — another directory, the
  environment, the network — is not in it.
  """

  @table __MODULE__

  @doc """
  A fingerprint for each test module, by module, with the files it covers.

  What ran as the application started is under every test, so those files
  are in every fingerprint.
  """
  def of(modules, references, root) do
    graph = graph()
    {shared, everyones} = shared(root)
    project = Map.keys(graph) |> MapSet.new()
    boot = for {file, _} <- Kalku.Baseline.Started.lines(), uniq: true, do: relative(file, root)

    for module <- modules, into: %{} do
      file = module.__ex_unit__().file

      named =
        references
        |> Map.get(file, [])
        |> Enum.map(&source_of(&1, root))
        |> Enum.filter(&MapSet.member?(project, &1))

      own = closure(boot ++ named, graph) |> MapSet.put(relative(file, root))
      {module, %{print: print(shared, own, root), covers: MapSet.union(own, everyones)}}
    end
  end

  @doc """
  The fingerprint of a module whose tests executed these files: none when
  any of them is one the fingerprint does not cover.
  """
  def standing(%{print: print, covers: covers}, reached) do
    if Enum.all?(reached, &MapSet.member?(covers, &1)), do: print, else: nil
  end

  @doc """
  Loads the test files with the compiler telling which modules each one
  names, and answers what `load` answered and those names, by file.
  """
  def tracing(load) do
    if :ets.whereis(@table) != :undefined, do: :ets.delete(@table)
    :ets.new(@table, [:named_table, :public, :bag])
    tracers = Code.get_compiler_option(:tracers)
    Code.put_compiler_option(:tracers, [__MODULE__ | tracers])

    try do
      {load.(), named()}
    after
      Code.put_compiler_option(:tracers, tracers)
      :ets.delete(@table)
    end
  end

  defp named do
    @table
    |> :ets.tab2list()
    |> Enum.group_by(&elem(&1, 0), &elem(&1, 1))
    |> Map.new(fn {file, modules} -> {file, Enum.uniq(modules)} end)
  end

  @doc false
  def trace({kind, _meta, module, _name, _arity}, env)
      when kind in [:remote_function, :remote_macro, :imported_function, :imported_macro],
      do: keep(env, module)

  def trace({kind, _meta, module, _opts}, env) when kind in [:require, :import],
    do: keep(env, module)

  def trace({:struct_expansion, _meta, module, _keys}, env), do: keep(env, module)
  def trace({:alias_reference, _meta, module}, env), do: keep(env, module)
  def trace(_event, _env), do: :ok

  defp keep(env, module) do
    :ets.insert(@table, {env.file, module})
    :ok
  end

  # What every test can reach without naming it: hashed, and its files.
  defp shared(root) do
    files =
      ["mix.exs", "mix.lock"] ++
        under(root, "config") ++
        under(root, "priv") ++
        under(root, "src") ++
        Enum.reject(under(root, "test"), &String.ends_with?(&1, "_test.exs"))

    versions = "Elixir #{System.version()} / OTP #{System.otp_release()}\n"
    {versions <> Enum.map_join(Enum.sort(files), &line(&1, root)), MapSet.new(files)}
  end

  defp print(shared, covers, root) do
    body = shared <> "--\n" <> Enum.map_join(Enum.sort(covers), &line(&1, root))
    :sha256 |> :crypto.hash(body) |> Base.encode16(case: :lower) |> binary_part(0, 24)
  end

  defp line(file, root), do: "#{file} #{hash(Path.join(root, file))}\n"

  # Read once per baseline, however many modules name it.
  defp hash(path) do
    case Process.get({__MODULE__, path}) do
      nil ->
        h =
          case File.read(path) do
            {:ok, bytes} -> :sha256 |> :crypto.hash(bytes) |> Base.encode16(case: :lower)
            {:error, _} -> "absent"
          end

        Process.put({__MODULE__, path}, h)
        h

      h ->
        h
    end
  end

  defp under(root, dir) do
    root
    |> Path.join(dir)
    |> Path.join("**")
    |> Path.wildcard(match_dot: true)
    |> Enum.filter(&File.regular?/1)
    |> Enum.map(&Path.relative_to(&1, Path.expand(root)))
  end

  defp closure(start, graph), do: closure(start, graph, MapSet.new())

  defp closure([], _graph, seen), do: seen

  defp closure([file | rest], graph, seen) do
    if MapSet.member?(seen, file),
      do: closure(rest, graph, seen),
      else: closure(Map.get(graph, file, []) ++ rest, graph, MapSet.put(seen, file))
  end

  defp source_of(module, root) do
    case Code.ensure_loaded(module) do
      {:module, _} -> relative(Kalku.Baseline.Cover.source(module), root)
      _ -> nil
    end
  end

  @doc """
  The project's files and the files each depends on, as `mix xref` says:
  the compiler's own record, not a guess of ours. Nothing on `xref`'s
  side leaves a file behind in the project.
  """
  def graph do
    case Kalku.Deps.capture(fn -> Mix.Task.rerun("xref", ["graph", "--format", "plain"]) end) do
      {:ok, output} -> edges(output)
    end
  rescue
    _ -> %{}
  catch
    _, _ -> %{}
  end

  @doc false
  def edges(output) do
    {graph, _} =
      output
      |> String.split("\n", trim: true)
      |> Enum.reduce({%{}, nil}, fn line, {graph, at} ->
        case Regex.run(~r/^[|`\s-]+(\S+)/, line) do
          [_, dep] when at != nil -> {Map.update!(graph, at, &[dep | &1]), at}
          nil -> {Map.put_new(graph, String.trim(line), []), String.trim(line)}
          _ -> {graph, at}
        end
      end)

    graph
  end

  defp relative(file, root) do
    text = to_string(file)

    case Path.relative_to(text, Path.expand(root)) do
      ^text -> Path.relative_to(text, File.cwd!())
      relative -> relative
    end
  end
end
