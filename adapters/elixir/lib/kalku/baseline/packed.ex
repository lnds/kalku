defmodule Kalku.Baseline.Packed do
  @moduledoc """
  Coverage written so that its size is what it says, not how often it says it.

  Line by line, coverage names every test of every line. A function most of
  the suite calls has each of its lines carry most of the suite, by name,
  and a project's coverage came to a hundred megabytes that were the same
  few thousand names over and over: longer to write and to read back than
  the suite took to run.

  Packed, a test is named once and referred to by where it stands in that
  list, and the lines of a file that the same tests reach are listed
  together.
  """

  @doc """
  Packs what each test reached.

  `tests` are `{id, lines}` in the order the ids are to be listed, each line
  a `{file, line}` with the file already as the protocol names it.
  """
  def pack(tests) do
    reached =
      tests
      |> Enum.with_index()
      |> Enum.flat_map(fn {{_id, lines}, at} -> for place <- lines, do: {place, at} end)
      |> Enum.group_by(fn {place, _} -> place end, fn {_, at} -> at end)
      |> Enum.group_by(
        fn {{file, _line}, ats} -> {file, Enum.uniq(Enum.sort(ats))} end,
        fn {{_file, line}, _} -> line end
      )
      |> Enum.sort()
      |> Enum.map(fn {{file, ats}, lines} ->
        %{"file" => file, "lines" => Enum.sort(lines), "tests" => ats}
      end)

    %{"tests" => Enum.map(tests, fn {id, _} -> id end), "reached" => reached}
  end

  @doc "How many times a test is named when the same coverage is written line by line."
  def references(tests), do: tests |> Enum.map(fn {_, lines} -> length(lines) end) |> Enum.sum()
end
