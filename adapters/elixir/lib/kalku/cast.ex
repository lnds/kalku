defmodule Kalku.Cast do
  @moduledoc """
  Casting one wekufe: splice the defect, load it, run the tests that reach
  it, put the original back.

  Nothing is written to the user's project. The source is spliced in
  memory and compiled from a string, so the file on disk is the file the
  developer left there, before the cast and after it.

  The original modules are kept before anything is compiled and reloaded
  afterwards on every path. A wekufe that outlived its cast would be
  attributed to the next one, and the next one's result would be a lie.
  """

  alias Kalku.Cast.{Beams, Entered, Tests}
  alias Kalku.Deps

  @doc """
  Starts a cast in a process of its own and returns what it takes to
  abort it.

  The caller keeps reading while this runs: an `abort` is only useful if
  it can arrive in the middle of the cast it stops.
  """
  def start(owner, id, root, wekufe, site, tests) do
    before = MapSet.new(Process.list())
    file = Path.join(root, site["file"])
    originals = originals_of(file)

    pid =
      spawn(fn ->
        {:ok, done} = run(root, wekufe, site, tests)
        send(owner, {:cast_done, id, done})
      end)

    %{id: id, pid: pid, wekufe: wekufe, file: file, originals: originals, before: before}
  end

  @doc """
  Stops a cast where it stands and puts the original modules back.

  Killing the casting process is not enough: ExUnit runs each test in a
  process it monitors rather than links, so a test looping forever
  outlives the cast that started it and would burn a core for the rest of
  the run. Everything that appeared while the cast ran is stopped —
  coarse, deliberately, because a wekufe is why any of it is there.

  Returns whether the runtime was restored, which is what decides between
  a kalku kept warm and a kalku recycled.
  """
  def abort(%{pid: pid, file: file, originals: originals, before: before}) do
    Process.exit(pid, :kill)
    stop_the_rest(before)
    Beams.clear()
    # What the cast compiled is loaded under its file by now, whatever stood
    # there when it started.
    restore(originals ++ originals_of(file)) == :ok
  rescue
    _ -> false
  end

  defp stop_the_rest(before) do
    for pid <- Process.list(), not MapSet.member?(before, pid), pid != self(), keep_out?(pid) do
      Process.exit(pid, :kill)
    end
  end

  # A process with a registered name belongs to something that named it —
  # the runtime, an application, the kalku itself — and outlives any one
  # cast. Unnamed processes that appeared during the cast are the cast's.
  defp keep_out?(pid) do
    case Process.info(pid, :registered_name) do
      {:registered_name, []} -> true
      _ -> false
    end
  end

  @doc """
  Casts a wekufe and says what it came to.

  Returns `{:ok, cast_done}`. A cast that could not compile is an outcome
  (`compile_error`), not a failure of the kalku.
  """
  def run(root, wekufe, site, tests) do
    started = System.monotonic_time(:millisecond)
    file = Path.join(root, site["file"])

    with {:ok, source} <- read(file),
         {:ok, spliced} <- splice(source, site) do
      cast(root, wekufe, file, spliced, tests, started, site)
    else
      {:error, message} -> {:ok, done(wekufe, "compile_error", started, message: message)}
    end
  end

  @doc """
  The wekufe's source: the original with the site's span replaced.

  The span is in bytes, which is what makes this exact regardless of what
  the file holds — a site is a token span, not a line.
  """
  def splice(source, %{"span" => %{"start" => start} = span} = site) do
    from = start["byte"]
    to = ending(span, from)

    cond do
      to < from or to > byte_size(source) ->
        {:error, "the site's span is not inside #{site["file"]}"}

      true ->
        {:ok,
         binary_part(source, 0, from) <>
           (site["replacement"] || "") <>
           binary_part(source, to, byte_size(source) - to)}
    end
  end

  def splice(_source, site), do: {:error, "the site for #{site["file"]} carries no span"}

  defp ending(%{"end" => %{"byte" => byte}}, _from), do: byte
  defp ending(_span, from), do: from

  # ---- the cast -----------------------------------------------------

  defp cast(root, wekufe, file, spliced, tests, started, site) do
    originals = originals_of(file)
    dependents = dependent_paths(root, site)
    borrowed = Enum.flat_map(dependents, &originals_of/1)

    case compile(spliced, file) do
      {:error, message} ->
        restore(originals ++ borrowed ++ originals_of(file))
        {:ok, done(wekufe, "compile_error", started, message: message)}

      {:ok, compiled} ->
        originals = originals ++ stood_in_for(compiled, originals)
        recompile(dependents)
        outcome = measure(root, compiled, originals, tests, site)
        restore(originals ++ borrowed)
        {:ok, done(wekufe, outcome.outcome, started, Map.to_list(Map.delete(outcome, :outcome)))}
    end
  end

  # A module that used this one's macro has the old expansion baked in.
  # Cast without recompiling it and the wekufe is loaded but not running
  # anywhere the caller can reach — a survivor that was never really
  # cast.
  defp dependent_paths(root, %{"reload" => "dependents", "file" => file}) do
    for dependent <- Deps.compile_dependents(file), do: Path.join(root, dependent)
  end

  defp dependent_paths(_root, _site), do: []

  defp recompile(paths) do
    for path <- paths, File.exists?(path) do
      safely(fn -> Code.compile_file(path) end)
    end

    :ok
  end

  defp safely(work) do
    work.()
  rescue
    _ -> :error
  catch
    _, _ -> :error
  end

  # A wekufe whose compiled code is the original's cannot be killed by any
  # test, because there is nothing there to notice. That is equivalence
  # proved rather than guessed, which is the only kind a kalku may report.
  defp measure(root, compiled, originals, tests, site) do
    if identical?(compiled, originals) do
      %{outcome: "equivalent", code_hash: hash_of(compiled)}
    else
      Beams.put(compiled)
      watched = Entered.watch(compiled, site)
      ran = Tests.run(root, tests)
      entered = Entered.entered?(watched)
      Entered.stop(watched)
      Beams.clear()
      ran |> looked_at(entered, site) |> Map.put(:code_hash, hash_of(compiled))
    end
  end

  # A wekufe that no test ran did not survive anything: every test passed
  # because none of them met it.
  defp looked_at(%{outcome: "survived"}, false, site),
    do: %{outcome: "no_coverage", message: Entered.unrun(site)}

  defp looked_at(ran, _entered, _site), do: ran

  defp identical?(compiled, originals) do
    md5s(compiled) == md5s(originals) and md5s(compiled) != %{}
  end

  defp md5s(binaries) do
    for {module, binary} <- binaries, into: %{} do
      case :beam_lib.md5(binary) do
        {:ok, {^module, md5}} -> {module, md5}
        _ -> {module, :unknown}
      end
    end
  end

  # One hash for the whole wekufe: the modules it produced, in order, so
  # the kaikai side can cache a result against the code that produced it.
  defp hash_of(compiled) do
    compiled
    |> Enum.sort_by(fn {module, _} -> module end)
    |> Enum.map_join("", fn {_, binary} -> binary end)
    |> then(&:crypto.hash(:sha256, &1))
    |> Base.encode16(case: :lower)
    |> binary_part(0, 32)
  end

  # ---- modules ------------------------------------------------------

  # What the file defines now, kept so it can be put back. A module with
  # no object code on disk cannot be restored, so it is not touched.
  defp originals_of(file) do
    for module <- modules_from(file), {:ok, binary} <- [object_code(module)], do: {module, binary}
  end

  # A module the suite has replaced — a mocking library puts a mock in its
  # place — is not loaded from this file when the cast starts, so it is not
  # among the originals, and the cast compiles its wekufe over the mock all
  # the same. Its original is the one on disk, like every other.
  defp stood_in_for(compiled, originals) do
    for {module, _} <- compiled,
        not List.keymember?(originals, module, 0),
        {:ok, binary} <- [object_code(module)],
        do: {module, binary}
  end

  defp modules_from(file) do
    source = String.to_charlist(Path.expand(file))

    for {module, _} <- :code.all_loaded(),
        compiled_from(module) == source,
        do: module
  end

  defp compiled_from(module) do
    module.module_info(:compile)[:source]
  rescue
    _ -> nil
  end

  defp object_code(module) do
    case :code.get_object_code(module) do
      {^module, binary, _path} -> {:ok, binary}
      :error -> :error
    end
  end

  defp compile(source, file) do
    {:ok, Code.compile_string(source, file)}
  rescue
    e -> {:error, Exception.message(e) |> first_line()}
  catch
    :exit, reason -> {:error, inspect(reason)}
    _kind, value -> {:error, inspect(value)}
  end

  # Loaded back under the file they came from. Loading a module under a
  # blank name erases where it was compiled from, and the next cast can
  # no longer tell that this module is the one its site belongs to — so
  # it neither restores it nor even finds it, and a wekufe from one cast
  # stays loaded for the rest of the run.
  defp restore(originals) do
    for {module, binary} <- originals do
      :code.purge(module)
      :code.load_binary(module, source_charlist(module), binary)
    end

    :ok
  end

  defp source_charlist(module) do
    case compiled_from(module) do
      nil -> ~c""
      source -> source
    end
  end

  defp read(file) do
    case File.read(file) do
      {:ok, source} -> {:ok, source}
      {:error, reason} -> {:error, "#{file}: #{:file.format_error(reason)}"}
    end
  end

  defp first_line(message), do: message |> String.split("\n", parts: 2) |> hd() |> String.trim()

  defp done(wekufe, outcome, started, extra) do
    %{
      "wekufe" => wekufe,
      "outcome" => outcome,
      "duration_ms" => System.monotonic_time(:millisecond) - started,
      "dirty" => Kalku.Runtime.dirty?(Mix.Project.config()[:app])
    }
    |> put_optional("killed_by", extra[:killed_by])
    |> put_optional("message", extra[:message])
    |> put_optional("code_hash", extra[:code_hash])
  end

  defp put_optional(body, _key, nil), do: body
  defp put_optional(body, key, value), do: Map.put(body, key, value)
end
