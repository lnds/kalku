defmodule Kalku.ProtocolTest do
  use ExUnit.Case, async: true

  alias Kalku.Protocol

  @fixtures Path.expand("../../../docs/protocol/fixtures", __DIR__)

  defp lines(sub) do
    Path.join([@fixtures, sub, "*.ndjson"])
    |> Path.wildcard()
    |> Enum.flat_map(fn path ->
      path
      |> File.read!()
      |> String.split("\n", trim: true)
      |> Enum.map(&{Path.basename(path), &1})
    end)
  end

  defp round_trip(direction, line) do
    {:ok, m} = Protocol.decode(line, direction)
    Protocol.encode(direction, m.type, m.id, m.body)
  end

  test "every request fixture decodes and re-encodes byte for byte" do
    for {file, line} <- lines("kalku/requests") do
      assert round_trip(:request, line) == line, "#{file}: #{line}"
    end
  end

  test "every reply fixture decodes and re-encodes byte for byte" do
    for {file, line} <- lines("kalku/replies") do
      assert round_trip(:reply, line) == line, "#{file}: #{line}"
    end
  end

  test "every site fixture encodes canonically" do
    for {_file, line} <- lines("shapes"), String.contains?(line, "\"site_id\"") do
      assert Protocol.encode_shape(:site, JSON.decode!(line)) == line
    end
  end

  test "invalid requests fail with the kind the fixture expects" do
    for entry <-
          File.read!(Path.join([@fixtures, "invalid", "kalku_requests.ndjson"]))
          |> String.split("\n", trim: true) do
      %{"expect" => kind, "line" => line} = JSON.decode!(entry)
      assert {:error, ^kind, _detail, _id} = Protocol.decode(line, :request), line
    end
  end

  test "an over-long line is rejected before parsing" do
    line = String.duplicate("x", Protocol.max_line_bytes() + 1)
    assert {:error, "line_too_long", _, nil} = Protocol.decode(line, :request)
  end
end
