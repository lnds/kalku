defmodule Kalku.Baseline.Cover do
  @moduledoc """
  Which lines each test executes.

  `:cover` counts per line, not per test, so the attribution comes from
  running one test at a time: the counters are cleared before each test
  and read after it. That is why a baseline with coverage runs the suite
  serially — a parallel suite would attribute one test's lines to
  whichever test happened to be running beside it.

  What it buys is the reason to bother: with coverage, a wekufe is cast
  against the handful of tests that execute its line instead of the whole
  suite. Speed is the product, and this is where most of it comes from.
  """

  @doc """
  Instruments the project's own modules, and only those.

  Dependencies and the kalku itself are left alone: nobody mutates them,
  so counting their lines would cost time and say nothing.
  """
  def start(app) when is_atom(app) do
    with {:ok, _} <- ensure_started(),
         ebin <- ebin_of(app),
         true <- File.dir?(ebin) do
      case :cover.compile_beam_directory(String.to_charlist(ebin)) do
        results when is_list(results) -> {:ok, Enum.count(results, &match?({:ok, _}, &1))}
        error -> {:error, "coverage could not instrument #{ebin}: #{inspect(error)}"}
      end
    else
      false -> {:error, "no compiled modules to measure in #{ebin_of(app)}"}
      {:error, reason} -> {:error, "coverage would not start: #{inspect(reason)}"}
    end
  end

  @doc "Forget what has been counted, so the next test starts from nothing."
  def reset, do: :cover.reset()

  @doc """
  The lines executed since the last reset, as `{file, line}`.

  A line `:cover` knows about but nobody ran is not reported: the protocol
  asks which tests cover a line, and a line no test covers simply has no
  entry.
  """
  def covered do
    case :cover.analyse(:coverage, :line) do
      {:result, entries, _failed} -> executed(entries)
      {:ok, entries} -> executed(entries)
      _ -> []
    end
  end

  defp executed(entries) do
    for {{module, line}, {calls, _missed}} <- entries, calls > 0, line > 0 do
      {source(module), line}
    end
  end

  @doc "Stop counting and give the modules back untouched."
  def stop do
    if Process.whereis(:cover_server), do: :cover.stop()
    :ok
  end

  defp ensure_started do
    case :cover.start() do
      {:ok, pid} -> {:ok, pid}
      {:error, {:already_started, pid}} -> {:ok, pid}
      error -> error
    end
  end

  defp ebin_of(app), do: Path.join([Mix.Project.build_path(), "lib", to_string(app), "ebin"])

  # A module names the file it was compiled from, which is what a site is
  # reported against.
  defp source(module) do
    module.module_info(:compile)[:source] |> to_string()
  rescue
    _ -> "unknown"
  end
end
