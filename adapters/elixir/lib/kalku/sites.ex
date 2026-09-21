defmodule Kalku.Sites do
  @moduledoc """
  Sites in one source file: parse with Elixir's own parser, collect spell
  candidates, keep only those that splice into code that still parses, and
  give each its protocol fields (span, semantic key, stable id).
  """

  alias Kalku.{Source, Walker}

  @parse_opts [columns: true, token_metadata: true, literal_encoder: &__MODULE__.encode_literal/2]

  @doc false
  def encode_literal(literal, meta), do: {:ok, {:__block__, meta, [literal]}}

  @doc """
  Finds the sites in `text` (the contents of `file`). Returns
  `{:ok, sites, dropped}`, where `dropped` counts candidates whose wekufe
  would not parse, or `{:error, message}` when the file does not parse.
  """
  def find(file, text, spells, exclude_calls) do
    case Code.string_to_quoted(text, @parse_opts) do
      {:ok, ast} -> {:ok, build(file, text, ast, spells, exclude_calls)}
      {:error, {meta, msg, token}} -> {:error, parse_message(meta, msg, token)}
    end
    |> case do
      {:ok, {sites, dropped}} -> {:ok, sites, dropped}
      error -> error
    end
  end

  defp build(file, text, ast, spells, exclude) do
    src = Source.new(text)
    file_hash = hash(text)

    {kept, dropped} =
      ast
      |> Walker.candidates(src, spells, exclude)
      |> Enum.map(&resolve(&1, src))
      |> Enum.uniq_by(&{&1.from_byte, &1.to_byte, &1.spell, &1.replacement})
      |> Enum.split_with(&splices?(&1, src))

    sites =
      kept
      |> Enum.sort_by(&{&1.from_byte, &1.to_byte, &1.spell})
      |> number()
      |> Enum.map(&to_site(&1, file, file_hash))

    {sites, length(dropped)}
  end

  defp resolve(c, src) do
    from = Source.byte(src, c.from)
    to = Source.byte(src, c.to)

    Map.merge(c, %{from_byte: from, to_byte: to, original: binary_part(src.text, from, to - from)})
  end

  defp splices?(c, src),
    do:
      c.original != c.replacement and
        Source.parses?(Source.splice(src, c.from_byte, c.to_byte, c.replacement))

  # Ordinal: 1-based occurrence of (spell, original) within the enclosing
  # declaration, in source order.
  defp number(cands) do
    {numbered, _} =
      Enum.map_reduce(cands, %{}, fn c, seen ->
        key = {c.enclosing, c.spell, c.original}
        n = Map.get(seen, key, 0) + 1
        {Map.put(c, :ordinal, n), Map.put(seen, key, n)}
      end)

    numbered
  end

  defp to_site(c, file, file_hash) do
    id = hash("#{file_hash}|#{c.from_byte}|#{c.to_byte}|#{c.spell}|#{c.replacement}")

    %{
      "site_id" => binary_part(id, 0, 12),
      "file" => file,
      "enclosing" => c.enclosing,
      "ordinal" => c.ordinal,
      "span" => %{"start" => position(c.from, c.from_byte), "end" => position(c.to, c.to_byte)},
      "spell" => c.spell,
      "original" => c.original,
      "replacement" => c.replacement,
      "reload" => "module"
    }
  end

  defp position({line, col}, byte), do: %{"line" => line, "col" => col, "byte" => byte}

  defp hash(data), do: :crypto.hash(:sha256, data) |> Base.encode16(case: :lower)

  defp parse_message(meta, msg, token) do
    line = if is_list(meta), do: meta[:line], else: meta
    "line #{line}: #{format(msg)}#{token}"
  end

  defp format({prefix, suffix}), do: "#{prefix}#{suffix} "
  defp format(msg), do: to_string(msg)
end
