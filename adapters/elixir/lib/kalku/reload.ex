defmodule Kalku.Reload do
  @moduledoc """
  Bringing changed files into the warm runtime.

  Between runs a developer edits, and the kalku that stayed warm is
  holding the code from before. Recompiling the changed files is not
  enough on its own: a module that used a macro from one of them has the
  old expansion baked into it, so its compile-time dependents come along.

  The suite is forgotten too. A test file may have changed, and a kalku
  that kept the modules it loaded first would keep measuring against
  tests that no longer exist.
  """

  alias Kalku.Deps

  @doc """
  Recompiles these files and whatever depends on them at compile time.

  Returns `{:ok, %{modules: [...], dependents: [...], duration_ms: n}}` —
  the modules that came from the files asked for, and the ones that came
  along because they had to.
  """
  def run(root, files) do
    started = System.monotonic_time(:millisecond)
    asked = Enum.map(files, &Path.join(root, &1))
    dependents = dependent_files(root, files)

    with {:ok, modules} <- compile_each(asked),
         {:ok, extra} <- compile_each(dependents) do
      Kalku.Baseline.forget()

      {:ok,
       %{
         "modules" => names(modules),
         "dependents" => names(extra),
         "duration_ms" => System.monotonic_time(:millisecond) - started
       }}
    end
  end

  # Asked-for files are not their own dependents, however the graph words
  # it: they are being recompiled anyway.
  defp dependent_files(root, files) do
    files
    |> Enum.flat_map(&Deps.compile_dependents/1)
    |> Enum.uniq()
    |> Kernel.--(files)
    |> Enum.map(&Path.join(root, &1))
  end

  defp compile_each(paths) do
    compiled =
      Enum.flat_map(paths, fn path ->
        case compile(path) do
          {:ok, modules} -> modules
          {:error, _} -> []
        end
      end)

    {:ok, compiled}
  end

  defp compile(path) do
    if File.exists?(path) do
      {:ok, Code.compile_file(path)}
    else
      {:error, "#{path} is not there any more"}
    end
  rescue
    e -> {:error, Exception.message(e)}
  catch
    _, value -> {:error, inspect(value)}
  end

  defp names(compiled),
    do: compiled |> Enum.map(fn {module, _} -> inspect(module) end) |> Enum.sort()
end
