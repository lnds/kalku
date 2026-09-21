defmodule Kalku.SitesTest do
  use ExUnit.Case, async: true

  alias Kalku.{Protocol, Schema, Sites, Source}

  @fixtures Path.expand("fixtures", __DIR__)
  @spells Schema.spells() -- ["foreign"]
  @exclude ["Logger.*"]

  # Set KALKU_UPDATE_GOLDENS=1 to rewrite the goldens after reviewing a change.
  @update System.get_env("KALKU_UPDATE_GOLDENS") == "1"

  defp sites(path) do
    {:ok, sites, 0} =
      Sites.find(Path.relative_to(path, @fixtures), File.read!(path), @spells, @exclude)

    sites
  end

  defp render(sites), do: Enum.map_join(sites, "", &(Protocol.encode_shape(:site, &1) <> "\n"))

  for path <- Path.wildcard(Path.join(@fixtures, "*/*.ex")) do
    @path path
    test "sites of #{Path.relative_to(path, @fixtures)} match the golden" do
      golden = Path.rootname(@path) <> ".sites.ndjson"
      actual = render(sites(@path))

      if @update, do: File.write!(golden, actual)
      assert actual == File.read!(golden)
    end
  end

  test "every site round-trips: its span holds `original`, and the wekufe parses" do
    files =
      Path.wildcard(Path.join(@fixtures, "*/*.ex")) ++
        Path.wildcard(Path.expand("../lib/**/*.ex", __DIR__))

    for path <- files, site <- sites_anywhere(path) do
      text = File.read!(path)
      from = site["span"]["start"]["byte"]
      to = site["span"]["end"]["byte"]

      assert binary_part(text, from, to - from) == site["original"], "#{path}: #{inspect(site)}"

      wekufe =
        binary_part(text, 0, from) <>
          site["replacement"] <> binary_part(text, to, byte_size(text) - to)

      assert Source.parses?(wekufe), "#{path}: #{inspect(site)}"
    end
  end

  defp sites_anywhere(path) do
    {:ok, sites, _dropped} = Sites.find(path, File.read!(path), @spells, @exclude)
    sites
  end

  test "byte offsets count UTF-8 bytes while columns count codepoints" do
    path = Path.join(@fixtures, "unicode/non_ascii.ex")
    text = File.read!(path)
    [site | _] = sites(path)
    %{"line" => line, "col" => col, "byte" => byte} = site["span"]["start"]

    prefix = text |> String.split("\n") |> Enum.take(line - 1) |> Enum.map_join(&(&1 <> "\n"))
    before = text |> String.split("\n") |> Enum.at(line - 1) |> String.slice(0, col - 1)

    assert site["original"] == "=="
    assert byte == byte_size(prefix) + byte_size(before)
    assert byte > String.length(prefix) + col - 1
  end

  test "excluded calls, docs, typespecs, raise messages, and identical branches yield no sites" do
    originals =
      Path.join(@fixtures, "exclude/skipped.ex") |> sites() |> Enum.map(& &1["original"])

    assert originals == ["if", ">", "0"]
  end

  test "ordinals number repeated (spell, original) pairs within a declaration" do
    ors =
      Path.join(@fixtures, "compare/guards_and_exprs.ex")
      |> sites()
      |> Enum.filter(&(&1["original"] == "or"))

    assert Enum.map(ors, & &1["ordinal"]) == [1, 2, 3, 4]
    assert Enum.uniq(Enum.map(ors, & &1["enclosing"])) == ["Fx.Compare.eq/2"]
  end

  test "site ids are stable and distinct" do
    path = Path.join(@fixtures, "arm/clauses.ex")
    ids = path |> sites() |> Enum.map(& &1["site_id"])
    assert ids == path |> sites() |> Enum.map(& &1["site_id"])
    assert ids == Enum.uniq(ids)
  end

  test "a file that does not parse is an error, not a crash" do
    assert {:error, message} =
             Sites.find("lib/broken.ex", "defmodule X do\n  def f(, do: 1\nend\n", @spells, [])

    assert message =~ "line 2"
  end
end
