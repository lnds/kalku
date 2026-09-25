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

  alias Kalku.Cast.Tests

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
      cast(root, wekufe, file, spliced, tests, started)
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

  defp cast(root, wekufe, file, spliced, tests, started) do
    originals = originals_of(file)

    case compile(spliced, file) do
      {:error, message} ->
        restore(originals)
        {:ok, done(wekufe, "compile_error", started, message: message)}

      {:ok, compiled} ->
        outcome = measure(root, compiled, originals, tests)
        restore(originals)
        {:ok, done(wekufe, outcome.outcome, started, Map.to_list(Map.delete(outcome, :outcome)))}
    end
  end

  # A wekufe whose compiled code is the original's cannot be killed by any
  # test, because there is nothing there to notice. That is equivalence
  # proved rather than guessed, which is the only kind a kalku may report.
  defp measure(root, compiled, originals, tests) do
    if identical?(compiled, originals) do
      %{outcome: "equivalent", code_hash: hash_of(compiled)}
    else
      Map.put(Tests.run(root, tests), :code_hash, hash_of(compiled))
    end
  end

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

  defp restore(originals) do
    for {module, binary} <- originals do
      :code.purge(module)
      :code.load_binary(module, ~c"", binary)
    end

    :ok
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
      "dirty" => false
    }
    |> put_optional("killed_by", extra[:killed_by])
    |> put_optional("message", extra[:message])
    |> put_optional("code_hash", extra[:code_hash])
  end

  defp put_optional(body, _key, nil), do: body
  defp put_optional(body, key, value), do: Map.put(body, key, value)
end
