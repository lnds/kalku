defmodule Kalku.Protocol do
  @moduledoc """
  Decoding and canonical encoding of kalku protocol lines.

  Decoded bodies keep JSON's shape (string keys) after being validated
  against `Kalku.Schema`; unknown fields are ignored, as the protocol
  requires. Errors carry the protocol error kind from docs/protocol.md.
  """

  alias Kalku.{Json, Schema}

  @max_line_bytes 4_194_304

  @type direction :: :request | :reply
  @type message :: %{type: String.t(), id: integer(), body: map()}
  @type error :: {:error, kind :: String.t(), detail :: String.t(), id :: integer() | nil}

  @doc "Longest accepted line, in bytes, excluding the newline."
  def max_line_bytes, do: @max_line_bytes

  @doc "Decodes one line (without its newline) as a message of the given direction."
  @spec decode(String.t(), direction()) :: {:ok, message()} | error()
  def decode(line, direction) when byte_size(line) > @max_line_bytes,
    do: {:error, "line_too_long", "#{byte_size(line)} bytes, direction #{direction}", nil}

  def decode(line, direction) do
    with {:ok, json} <- parse(line),
         {:ok, type, id} <- head(json),
         {:ok, fields} <- table(direction, type, id),
         :ok <- check_fields(json, fields, "") |> tag(id) do
      {:ok, %{type: type, id: id, body: Map.drop(json, ["type", "id"])}}
    end
  end

  @doc "Encodes a message canonically: `type`, `id`, then the schema's fields; absent ones omitted."
  @spec encode(direction(), String.t(), integer(), map()) :: String.t()
  def encode(direction, type, id, body) do
    fields =
      Schema.message(direction, type) || raise ArgumentError, "unknown #{direction} #{type}"

    {:object, pairs} = object(body, fields)
    Json.encode({:object, [{"type", type}, {"id", id} | pairs]})
  end

  @doc "Encodes a value of a shared shape (`:site`, `:span`, …) canonically."
  def encode_shape(name, value), do: Json.encode(object(value, Schema.shape(name)))

  # ---- decode ------------------------------------------------------------

  defp parse(line) do
    case Json.decode(line) do
      {:ok, map} when is_map(map) -> {:ok, map}
      {:ok, other} -> {:error, "not_object", "expected an object, got #{shape_name(other)}", nil}
      {:error, _} -> {:error, "not_json", "not valid JSON", nil}
    end
  end

  defp head(%{"type" => type} = json) when is_binary(type) do
    case json do
      %{"id" => id} when is_integer(id) -> {:ok, type, id}
      _ -> {:error, "missing_id", "id: expected an integer", nil}
    end
  end

  defp head(_), do: {:error, "missing_type", "type: expected a string", nil}

  defp table(direction, type, id) do
    case Schema.message(direction, type) do
      nil -> {:error, "unknown_type", "no #{direction} `#{type}`", id}
      fields -> {:ok, fields}
    end
  end

  defp tag(:ok, _id), do: :ok
  defp tag({:bad, detail}, id), do: {:error, "bad_field", detail, id}

  defp check_fields(obj, fields, path) do
    Enum.reduce_while(fields, :ok, fn {name, type}, :ok ->
      key = Atom.to_string(name)

      case check(Map.get(obj, key), type, join(path, key)) do
        :ok -> {:cont, :ok}
        bad -> {:halt, bad}
      end
    end)
  end

  defp check(nil, {:opt, _}, _path), do: :ok
  defp check(value, {:opt, type}, path), do: check(value, type, path)
  defp check(nil, _type, path), do: {:bad, "#{path}: required"}
  defp check(v, :int, _) when is_integer(v), do: :ok
  defp check(v, :string, _) when is_binary(v), do: :ok
  defp check(v, :bool, _) when is_boolean(v), do: :ok
  defp check(v, {:list, type}, path) when is_list(v), do: check_list(v, type, path)

  defp check(v, {:shape, name}, path) when is_map(v),
    do: check_fields(v, Schema.shape(name), path)

  defp check(v, {:enum, name}, path) when is_binary(v), do: check_enum(v, name, path)
  defp check(v, :strmap, path) when is_map(v), do: check_strmap(v, path)
  defp check(v, :scope, path), do: check_scope(v, path)

  defp check(v, type, path),
    do: {:bad, "#{path}: expected #{type_name(type)}, got #{shape_name(v)}"}

  defp check_list(items, type, path) do
    items
    |> Enum.with_index()
    |> Enum.reduce_while(:ok, fn {item, i}, :ok ->
      case check(item, type, "#{path}[#{i}]") do
        :ok -> {:cont, :ok}
        bad -> {:halt, bad}
      end
    end)
  end

  defp check_enum(v, name, path) do
    if v in Schema.enum(name), do: :ok, else: {:bad, "#{path}: unknown #{name} #{inspect(v)}"}
  end

  defp check_strmap(map, path) do
    case Enum.find(map, fn {_k, v} -> not is_binary(v) end) do
      nil -> :ok
      {k, v} -> {:bad, "#{join(path, k)}: expected String, got #{shape_name(v)}"}
    end
  end

  defp check_scope(%{"all" => true} = s, _path) when map_size(s) == 1, do: :ok
  defp check_scope(%{"since" => r} = s, _path) when map_size(s) == 1 and is_binary(r), do: :ok

  defp check_scope(%{"files" => fs} = s, path) when map_size(s) == 1,
    do: check(fs, {:list, :string}, join(path, "files"))

  defp check_scope(_, path),
    do: {:bad, "#{path}: expected exactly one of all: true, since, files"}

  # ---- encode ------------------------------------------------------------

  defp object(map, fields) do
    pairs =
      Enum.flat_map(fields, fn {name, type} ->
        key = Atom.to_string(name)

        case Map.get(map, key) do
          nil -> []
          v -> [{key, value(v, type)}]
        end
      end)

    {:object, pairs}
  end

  defp value(v, {:opt, type}), do: value(v, type)
  defp value(v, {:list, type}), do: Enum.map(v, &value(&1, type))
  defp value(v, {:shape, name}), do: object(v, Schema.shape(name))
  defp value(v, :strmap), do: {:object, Enum.sort(v)}
  defp value(v, :scope), do: {:object, Enum.to_list(v)}
  defp value(v, _scalar), do: v

  # ---- names -------------------------------------------------------------

  defp join("", key), do: key
  defp join(path, key), do: "#{path}.#{key}"

  defp type_name(:int), do: "Int"
  defp type_name(:string), do: "String"
  defp type_name(:bool), do: "Bool"
  defp type_name(:strmap), do: "Object of strings"
  defp type_name({:list, _}), do: "Array"
  defp type_name({:shape, name}), do: "#{name} object"
  defp type_name({:enum, name}), do: "#{name} string"

  defp shape_name(v) when is_map(v), do: "Object"
  defp shape_name(v) when is_list(v), do: "Array"
  defp shape_name(v) when is_binary(v), do: "String"
  defp shape_name(v) when is_boolean(v), do: "Bool"
  defp shape_name(v) when is_number(v), do: "Number"
  defp shape_name(nil), do: "null"
end
