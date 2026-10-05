defmodule Kalku.Json do
  @moduledoc """
  The protocol's JSON, encoded and decoded here rather than by a library.

  The kalku runs inside the user's project, on the user's Elixir: the
  built-in `JSON` module only exists from 1.18, and a dependency could
  conflict with one of theirs. So the codec is the kalku's own, and the
  same code runs on every version.

  Encoding is canonical. Elixir maps have no order, so objects are built
  as `{:object, [{key, value}]}` and emitted in that order; a plain map is
  emitted with its keys sorted. Strings are raw UTF-8, with only `"`,
  `\\`, and control characters escaped.
  """

  import Bitwise

  @type value ::
          {:object, [{String.t(), value()}]}
          | %{optional(String.t()) => value()}
          | [value()]
          | String.t()
          | integer()
          | float()
          | boolean()
          | nil

  # ---- encode ------------------------------------------------------------

  @doc "Encodes a value to a compact JSON string."
  @spec encode(value()) :: String.t()
  def encode(value), do: value |> iodata() |> IO.iodata_to_binary()

  defp iodata({:object, pairs}) do
    members = Enum.map(pairs, fn {k, v} -> [string(k), ?:, iodata(v)] end)
    [?{, Enum.intersperse(members, ?,), ?}]
  end

  defp iodata(map) when is_map(map) do
    iodata({:object, map |> Enum.map(fn {k, v} -> {key(k), v} end) |> Enum.sort()})
  end

  defp iodata(list) when is_list(list),
    do: [?[, Enum.intersperse(Enum.map(list, &iodata/1), ?,), ?]]

  defp iodata(nil), do: "null"
  defp iodata(true), do: "true"
  defp iodata(false), do: "false"
  defp iodata(atom) when is_atom(atom), do: string(Atom.to_string(atom))
  defp iodata(text) when is_binary(text), do: string(text)
  defp iodata(n) when is_integer(n), do: Integer.to_string(n)
  defp iodata(n) when is_float(n), do: :erlang.float_to_binary(n, [:short])

  defp key(k) when is_binary(k), do: k
  defp key(k) when is_atom(k), do: Atom.to_string(k)

  defp string(text), do: [?", escape(text, text, 0, 0, []), ?"]

  # Walks the string once and copies the runs that need no escaping as
  # slices of the original, so a long clean string costs one reference.
  defp escape(<<c, rest::binary>>, text, from, len, acc) when c < 0x20 or c == ?" or c == ?\\ do
    acc = [acc, binary_part(text, from, len), escaped(c)]
    escape(rest, text, from + len + 1, 0, acc)
  end

  defp escape(<<c::utf8, rest::binary>>, text, from, len, acc) do
    escape(rest, text, from, len + byte_size(<<c::utf8>>), acc)
  end

  defp escape(<<>>, text, from, len, acc), do: [acc, binary_part(text, from, len)]

  defp escape(<<byte, _::binary>>, _text, _from, _len, _acc) do
    raise ArgumentError, "invalid UTF-8 byte 0x#{Integer.to_string(byte, 16)} in a JSON string"
  end

  defp escaped(?"), do: "\\\""
  defp escaped(?\\), do: "\\\\"
  defp escaped(?\b), do: "\\b"
  defp escaped(?\t), do: "\\t"
  defp escaped(?\n), do: "\\n"
  defp escaped(?\f), do: "\\f"
  defp escaped(?\r), do: "\\r"

  defp escaped(c) do
    ["\\u00", c |> Integer.to_string(16) |> String.pad_leading(2, "0")]
  end

  # ---- decode ------------------------------------------------------------

  @doc """
  Decodes one JSON document. Objects become maps with string keys; a
  number is an integer unless it has a fraction or an exponent.
  """
  @spec decode(binary()) :: {:ok, term()} | {:error, :invalid}
  def decode(text) when is_binary(text) do
    {value, rest} = value(skip(text))

    case skip(rest) do
      <<>> -> {:ok, value}
      _ -> {:error, :invalid}
    end
  catch
    :invalid -> {:error, :invalid}
  end

  @doc "Decodes one JSON document, raising on text that is not JSON."
  @spec decode!(binary()) :: term()
  def decode!(text) do
    case decode(text) do
      {:ok, value} -> value
      {:error, :invalid} -> raise ArgumentError, "not valid JSON: #{inspect(text, limit: 80)}"
    end
  end

  defp skip(<<c, rest::binary>>) when c in [?\s, ?\t, ?\n, ?\r], do: skip(rest)
  defp skip(rest), do: rest

  defp value(<<"null", rest::binary>>), do: {nil, rest}
  defp value(<<"true", rest::binary>>), do: {true, rest}
  defp value(<<"false", rest::binary>>), do: {false, rest}
  defp value(<<?", rest::binary>>), do: text(rest, rest, 0, [])
  defp value(<<?[, rest::binary>>), do: array(skip(rest))
  defp value(<<?{, rest::binary>>), do: object(skip(rest))
  defp value(<<c, _::binary>> = rest) when c == ?- or c in ?0..?9, do: number(rest)
  defp value(_), do: throw(:invalid)

  defp array(<<?], rest::binary>>), do: {[], rest}
  defp array(rest), do: elements(rest, [])

  defp elements(rest, acc) do
    {value, rest} = value(rest)

    case skip(rest) do
      <<?,, rest::binary>> -> elements(skip(rest), [value | acc])
      <<?], rest::binary>> -> {Enum.reverse([value | acc]), rest}
      _ -> throw(:invalid)
    end
  end

  defp object(<<?}, rest::binary>>), do: {%{}, rest}
  defp object(rest), do: members(rest, %{})

  defp members(<<?", rest::binary>>, acc) do
    {key, rest} = text(rest, rest, 0, [])

    case skip(rest) do
      <<?:, rest::binary>> ->
        {value, rest} = value(skip(rest))
        acc = Map.put(acc, key, value)

        case skip(rest) do
          <<?,, rest::binary>> -> members(skip(rest), acc)
          <<?}, rest::binary>> -> {acc, rest}
          _ -> throw(:invalid)
        end

      _ ->
        throw(:invalid)
    end
  end

  defp members(_, _), do: throw(:invalid)

  # `from` is the start of the run being read, `len` its length so far.
  defp text(<<?", rest::binary>>, from, len, acc) do
    {IO.iodata_to_binary([acc, binary_part(from, 0, len)]), rest}
  end

  defp text(<<?\\, rest::binary>>, from, len, acc) do
    {char, rest} = unescape(rest)
    text(rest, rest, 0, [acc, binary_part(from, 0, len), char])
  end

  defp text(<<c::utf8, rest::binary>>, from, len, acc) when c >= 0x20 do
    text(rest, from, len + byte_size(<<c::utf8>>), acc)
  end

  defp text(_, _, _, _), do: throw(:invalid)

  defp unescape(<<?", rest::binary>>), do: {?", rest}
  defp unescape(<<?\\, rest::binary>>), do: {?\\, rest}
  defp unescape(<<?/, rest::binary>>), do: {?/, rest}
  defp unescape(<<?b, rest::binary>>), do: {?\b, rest}
  defp unescape(<<?f, rest::binary>>), do: {?\f, rest}
  defp unescape(<<?n, rest::binary>>), do: {?\n, rest}
  defp unescape(<<?r, rest::binary>>), do: {?\r, rest}
  defp unescape(<<?t, rest::binary>>), do: {?\t, rest}

  defp unescape(<<?u, hex::binary-size(4), rest::binary>>) do
    case unit(hex) do
      high when high in 0xD800..0xDBFF -> pair(high, rest)
      low when low in 0xDC00..0xDFFF -> throw(:invalid)
      code -> {<<code::utf8>>, rest}
    end
  end

  defp unescape(_), do: throw(:invalid)

  # A code point beyond the basic plane arrives as two escapes.
  defp pair(high, <<"\\u", hex::binary-size(4), rest::binary>>) do
    case unit(hex) do
      low when low in 0xDC00..0xDFFF ->
        {<<0x10000 + ((high &&& 0x3FF) <<< 10) + (low &&& 0x3FF)::utf8>>, rest}

      _ ->
        throw(:invalid)
    end
  end

  defp pair(_, _), do: throw(:invalid)

  defp unit(hex) do
    case Integer.parse(hex, 16) do
      {code, ""} when code >= 0 -> code
      _ -> throw(:invalid)
    end
  end

  defp number(rest) do
    {sign, rest} = sign(rest)
    {whole, rest} = whole(rest)
    {fraction, rest} = fraction(rest)
    {exponent, rest} = exponent(rest)

    if fraction == "" and exponent == "" do
      {String.to_integer(sign <> whole), rest}
    else
      # Erlang reads a float only with a fraction, which JSON may omit.
      fraction = if fraction == "", do: ".0", else: fraction
      {float(sign <> whole <> fraction <> exponent), rest}
    end
  end

  defp float(text) do
    String.to_float(text)
  rescue
    ArgumentError -> throw(:invalid)
  end

  defp sign(<<?-, rest::binary>>), do: {"-", rest}
  defp sign(rest), do: {"", rest}

  defp whole(<<?0, rest::binary>>), do: {"0", rest}
  defp whole(rest), do: digits(rest)

  defp fraction(<<?., rest::binary>>) do
    {digits, rest} = digits(rest)
    {"." <> digits, rest}
  end

  defp fraction(rest), do: {"", rest}

  defp exponent(<<e, rest::binary>>) when e in [?e, ?E] do
    {sign, rest} =
      case rest do
        <<?+, rest::binary>> -> {"", rest}
        <<?-, rest::binary>> -> {"-", rest}
        _ -> {"", rest}
      end

    {digits, rest} = digits(rest)
    {"e" <> sign <> digits, rest}
  end

  defp exponent(rest), do: {"", rest}

  # At least one digit, or the number is not one.
  defp digits(rest) do
    case take_digits(rest, 0) do
      0 -> throw(:invalid)
      n -> {binary_part(rest, 0, n), binary_part(rest, n, byte_size(rest) - n)}
    end
  end

  defp take_digits(<<c, rest::binary>>, n) when c in ?0..?9, do: take_digits(rest, n + 1)
  defp take_digits(_, n), do: n
end
