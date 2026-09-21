defmodule Kalku.Source do
  @moduledoc """
  A source file as text: positions, slices, and splices.

  Elixir's AST columns count codepoints; the protocol also wants byte
  offsets, so every position is resolved against the line's text.
  """

  defstruct [:text, :lines, :line_starts]

  @type t :: %__MODULE__{text: String.t(), lines: tuple(), line_starts: tuple()}
  @type pos :: {line :: pos_integer(), col :: pos_integer()}

  @doc "Indexes a file's text."
  @spec new(String.t()) :: t()
  def new(text) do
    lines = String.split(text, "\n")
    starts = lines |> Enum.scan(0, fn l, acc -> acc + byte_size(l) + 1 end) |> Enum.drop(-1)
    %__MODULE__{text: text, lines: List.to_tuple(lines), line_starts: List.to_tuple([0 | starts])}
  end

  @doc "The text of a 1-based line, without its newline; `\"\"` past the end."
  def line(%__MODULE__{lines: lines}, n) when n >= 1 and n <= tuple_size(lines),
    do: elem(lines, n - 1)

  def line(_src, _n), do: ""

  @doc "Byte offset of a `{line, col}` position."
  @spec byte(t(), pos()) :: non_neg_integer()
  def byte(%__MODULE__{line_starts: starts, text: text}, {line, _col})
      when line > tuple_size(starts),
      do: byte_size(text)

  def byte(%__MODULE__{line_starts: starts} = src, {line, col}) do
    prefix = src |> line(line) |> String.slice(0, col - 1)
    elem(starts, line - 1) + byte_size(prefix)
  end

  @doc "Position `n` codepoints after `pos` on the same line."
  def advance({line, col}, n), do: {line, col + n}

  @doc "The text between two positions."
  def slice(src, from, to) do
    a = byte(src, from)
    binary_part(src.text, a, byte(src, to) - a)
  end

  @doc "True when `text` appears at `pos`."
  def at?(src, {line, col}, text) do
    String.slice(line(src, line), col - 1, String.length(text)) == text
  end

  @doc "True when only whitespace precedes `pos` on its line."
  def line_start?(src, {line, col}) do
    src |> line(line) |> String.slice(0, col - 1) |> String.trim() == ""
  end

  @doc "The text with the bytes `[from, to)` replaced."
  def splice(%__MODULE__{text: text}, from, to, replacement) do
    binary_part(text, 0, from) <> replacement <> binary_part(text, to, byte_size(text) - to)
  end

  @doc "True when the text parses as Elixir."
  def parses?(text), do: match?({:ok, _}, Code.string_to_quoted(text))
end
