defmodule Kalku.Json do
  @moduledoc """
  Canonical JSON encoding. Elixir maps have no order, so objects are built
  as `{:object, [{key, value}]}` and emitted in that order; everything else
  delegates to the built-in `JSON` module, whose escaping matches the
  protocol (raw UTF-8, only `"`, `\\`, and control characters escaped).
  """

  @type value ::
          {:object, [{String.t(), value()}]}
          | [value()]
          | String.t()
          | integer()
          | float()
          | boolean()
          | nil

  @doc "Encodes a value to a compact JSON string."
  @spec encode(value()) :: String.t()
  def encode(value), do: value |> iodata() |> IO.iodata_to_binary()

  defp iodata({:object, pairs}) do
    members = Enum.map(pairs, fn {k, v} -> [JSON.encode!(k), ?:, iodata(v)] end)
    [?{, Enum.intersperse(members, ?,), ?}]
  end

  defp iodata(list) when is_list(list),
    do: [?[, Enum.intersperse(Enum.map(list, &iodata/1), ?,), ?]]

  defp iodata(scalar), do: JSON.encode!(scalar)
end
