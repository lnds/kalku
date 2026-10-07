# Writes a Mix project of many small tests, for what no public project
# shows on demand: a suite of thousands, and one trait at a time of those
# that have ended a run.
#
#   elixir tools/field/generate.exs <dir> [--modules=N] [--tests=N] [--trait=NAME]...
#
# N modules of N functions, one test module for each and one test for each
# function, so a wekufe has exactly one test that can kill it. The project
# does not depend on the kalku. Traits:
#
#   unready   a test module whose `setup_all` raises
#   stdout    every test prints a line of JSON to stdout
#   tool      a test starts an executable that is not installed
#   mimic     a test replaces a module for every process, with Mimic
{opts, [dir], _} =
  OptionParser.parse(System.argv(), strict: [modules: :integer, tests: :integer, trait: :keep])

modules = Keyword.get(opts, :modules, 300)
tests = Keyword.get(opts, :tests, 10)
traits = Keyword.get_values(opts, :trait)

known = ~w(unready stdout tool mimic)

for trait <- traits, trait not in known do
  IO.puts(:stderr, "generate: no trait `#{trait}`; there are #{Enum.join(known, ", ")}")
  System.halt(2)
end

put = fn path, text ->
  path = Path.join(dir, path)
  File.mkdir_p!(Path.dirname(path))
  File.write!(path, text)
end

deps = if "mimic" in traits, do: ~s([{:mimic, "~> 1.7", only: :test}]), else: "[]"

put.("mix.exs", """
defmodule Field.MixProject do
  use Mix.Project

  def project, do: [app: :field, version: "0.1.0", elixir: "~> 1.16", deps: #{deps}]
  def application, do: [extra_applications: [:logger]]
end
""")

put.(".gitignore", "/_build/\n/deps/\n")

helper = if "mimic" in traits, do: "Mimic.copy(Field.M1)\n", else: ""
put.("test/test_helper.exs", helper <> "ExUnit.start()\n")

said = if "stdout" in traits, do: ~s|    IO.puts(~s({"message":"a test ran","level":"info"}))\n|, else: ""

for m <- 1..modules do
  functions =
    for f <- 1..tests do
      "  def f#{f}(x), do: if(x > #{f}, do: x + #{f}, else: #{f})\n"
    end

  put.("lib/field/m#{m}.ex", "defmodule Field.M#{m} do\n#{functions}end\n")

  cases =
    for f <- 1..tests do
      """
        test "f#{f}" do
      #{said}    assert Field.M#{m}.f#{f}(#{f}) == #{f}
          assert Field.M#{m}.f#{f}(#{f + 1}) == #{2 * f + 1}
        end
      """
    end

  put.("test/field/m#{m}_test.exs", """
  defmodule Field.M#{m}Test do
    use ExUnit.Case, async: true

  #{cases}end
  """)
end

if "unready" in traits do
  put.("test/field/unready_test.exs", """
  defmodule Field.UnreadyTest do
    use ExUnit.Case

    setup_all do
      raise "nothing here can be set up"
    end

    test "never runs" do
      assert Field.M1.f1(0) == 1
    end
  end
  """)
end

if "tool" in traits do
  put.("test/field/tool_test.exs", """
  defmodule Field.ToolTest do
    use ExUnit.Case

    test "the tool answers" do
      assert {_, 0} = System.cmd("kalku-field-tool-nobody-installed", ["--version"])
    end
  end
  """)
end

if "mimic" in traits do
  put.("test/field/replaced_test.exs", """
  defmodule Field.ReplacedTest do
    use ExUnit.Case
    use Mimic

    setup :set_mimic_global

    test "a module replaced for every process" do
      stub(Field.M1, :f1, fn _ -> :replaced end)
      assert Task.await(Task.async(fn -> Field.M1.f1(0) end)) == :replaced
    end
  end
  """)
end

IO.puts(:stderr, "generate: #{modules} modules, #{modules * tests} tests, in #{dir}")
