defmodule Kalku.Strays do
  @moduledoc """
  What running a suite leaves in the user's project, taken away again.

  A mocking library that replaces a module under `:cover` first exports
  the module's counters to `<Module>-<os pid>.coverdata` in the current
  directory — the project's root — and removes the file when it puts the
  module back, in an `after_suite` callback. A kalku leaves those
  callbacks out so the suite stays set up between runs
  (`Kalku.Baseline`), so the file would stay where it was written. Nothing
  reads it again here, and the kalku never writes in the project, so it
  is removed as soon as the test that caused it is over.

  The name carries this runtime's own process id: only what this kalku
  caused is ever removed.
  """

  @doc "Removes the cover exports this runtime left in the current directory."
  @spec sweep() :: :ok
  def sweep do
    Enum.each(Path.wildcard("*-#{:os.getpid()}.coverdata"), &File.rm/1)
  end
end
