defmodule Kalku.Baseline.CallsTest do
  use ExUnit.Case, async: false

  alias Kalku.Baseline.Calls

  @shape Kalku.CallsTest.Shape

  @source """
  defmodule Kalku.CallsTest.Shape do
    def a(x) do
      x + 1
    end

    def b(0), do: 0
    def b(x), do: x

    def c(x) do
      x * 2
    end
  end
  """

  setup do
    dir = Path.join(System.tmp_dir!(), "kalku-calls-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    kept = Code.get_compiler_option(:debug_info)
    Code.put_compiler_option(:debug_info, true)
    [{@shape, beam}] = Code.compile_string(@source, "lib/shape.ex")
    Code.put_compiler_option(:debug_info, kept)
    path = Path.join(dir, "#{@shape}.beam")
    File.write!(path, beam)

    with {:error, {:already_started, _}} <- :cover.start(), do: :ok
    {:ok, @shape} = :cover.compile_beam(String.to_charlist(path))

    on_exit(fn ->
      Calls.stop()
      :cover.stop()
      File.rm_rf!(dir)
      :code.purge(@shape)
      :code.delete(@shape)
    end)

    :ok
  end

  defp lines(places), do: places |> Enum.map(fn {_, line} -> line end) |> Enum.sort()

  # Each clause is credited from its head to its last line, never into the
  # clause after it; the last clause of the module has none after it.
  test "a function is written from each clause's head to its last line" do
    assert lines(Calls.written(@shape, [{:b, 1}, {:c, 1}])) == [6, 7, 9, 10]
    assert lines(Calls.written(@shape, [{:a, 1}])) == [2, 3]
  end

  test "a call is credited with the lines of the function it entered, and no other" do
    Calls.start([@shape])
    apply(@shape, :c, [1])
    assert lines(Calls.taken()) == [9, 10]

    apply(@shape, :b, [0])
    assert lines(Calls.taken()) == [6, 7]
  end
end
