defmodule Kalku.Baseline.CoverTest do
  use ExUnit.Case, async: false

  alias Kalku.Baseline.Cover

  # Named here and made in `setup_all`, so nothing calls them by a name the
  # compiler would look for.
  @one Kalku.CoverTest.One
  @two Kalku.CoverTest.Two
  @modules [@one, @two]

  # Two modules for `:cover` to count, as a project's are, without a project.
  setup_all do
    dir = Path.join(System.tmp_dir!(), "kalku-cover-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    kept = Code.get_compiler_option(:debug_info)
    Code.put_compiler_option(:debug_info, true)

    for name <- @modules do
      source = "defmodule #{inspect(name)} do\n  def f(x) do\n    x + 1\n  end\nend\n"
      [{^name, beam}] = Code.compile_string(source, "lib/#{Macro.underscore(name)}.ex")
      File.write!(Path.join(dir, "#{name}.beam"), beam)
    end

    Code.put_compiler_option(:debug_info, kept)

    on_exit(fn ->
      File.rm_rf!(dir)

      for name <- @modules do
        :code.purge(name)
        :code.delete(name)
      end
    end)

    {:ok, dir: dir}
  end

  setup %{dir: dir} do
    with {:error, {:already_started, _}} <- :cover.start(), do: :ok

    for name <- @modules do
      {:ok, ^name} = :cover.compile_beam(String.to_charlist(Path.join(dir, "#{name}.beam")))
    end

    on_exit(fn -> :cover.stop() end)
    Cover.reset()
    :ok
  end

  defp lines(covered), do: Enum.sort(for {file, line} <- covered, do: {Path.basename(file), line})

  # Reading every module's counters after every test is what made a baseline
  # take hours: the cost is per module read, and a test enters few of them.
  test "only a module that ran since the last reset is entered" do
    assert Cover.entered() == []

    apply(@one, :f, [1])

    assert Cover.entered() == [@one]
  end

  test "the modules entered hold every line the whole reading holds" do
    apply(@one, :f, [1])
    entered = Cover.entered()
    narrow = Cover.covered(entered)

    apply(@one, :f, [1])

    assert lines(narrow) == [{"one.ex", 3}]
    assert lines(Cover.covered()) == lines(narrow)
  end

  # What one test ran must not be read again as the next one's.
  test "a module that was read and reset is not entered again until it runs" do
    apply(@one, :f, [1])
    entered = Cover.entered()
    Cover.covered(entered)
    Cover.reset(entered)

    apply(@two, :f, [1])

    assert Cover.entered() == [@two]
    assert lines(Cover.covered()) == [{"two.ex", 3}]
  end

  test "no module entered is no line, and nothing is asked of `:cover`" do
    assert Cover.covered([]) == []
  end
end
