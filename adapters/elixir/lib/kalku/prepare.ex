defmodule Kalku.Prepare do
  @moduledoc """
  Compiling the user's project into the reni and starting the test
  environment, once, before any wekufe is cast.

  Everything this builds lives in the reni. A kalku that compiled into the
  project's own `_build` would leave a user's tree different from how it
  found it — and would do it with mutated code, which is worse. So the
  isolation is checked rather than assumed: if the build path is not
  inside the reni, nothing is compiled and the run stops.

  Protocol consolidation is turned off in that build. A consolidated
  protocol is compiled from every implementation at once, so a wekufe cast
  into a `defimpl` would be quietly ignored and counted as a survivor —
  a hole that is not a hole.
  """

  @doc """
  Compiles into the reni and starts the project's applications.

  Returns `{:ok, %{duration_ms: ms, modules: n}}`, or `{:error, code,
  message}` naming what made it impossible.
  """
  def run(reni, env \\ %{}) do
    started = System.monotonic_time(:millisecond)

    with :ok <- test_env(),
         :ok <- inside_reni(reni),
         :ok <- partition(env),
         {:ok, modules} <- compile(),
         :ok <- start_apps(),
         :ok <- Kalku.Runtime.mark(Mix.Project.config()[:app]) do
      {:ok, %{duration_ms: System.monotonic_time(:millisecond) - started, modules: modules}}
    end
  end

  @doc "Where the build is going, which is the reni or nowhere."
  def build_path, do: Mix.Project.build_path()

  # A build path outside the reni means the kalku was summoned without the
  # environment the protocol promises it. Compiling anyway would write into
  # the user's project.
  defp inside_reni(reni) when is_binary(reni) and reni != "" do
    build = Path.expand(build_path())
    home = Path.expand(reni)

    if build == home or String.starts_with?(build, home <> "/") do
      :ok
    else
      {:error, "reni_not_isolated",
       "the build path is #{build}, outside the reni #{home}; " <>
         "summon this kalku with MIX_BUILD_PATH inside the reni"}
    end
  end

  defp inside_reni(_), do: {:error, "reni_not_isolated", "no reni was given in `hello`"}

  defp test_env do
    if Mix.env() == :test do
      :ok
    else
      {:error, "wrong_env",
       "this kalku runs in MIX_ENV=test, not #{Mix.env()}; the suite is the oracle"}
    end
  end

  # Each kalku needs its own test database when the project partitions,
  # which is why `hello` carries an environment at all.
  defp partition(env) do
    case Map.fetch(env, "MIX_TEST_PARTITION") do
      {:ok, value} when is_binary(value) -> System.put_env("MIX_TEST_PARTITION", value)
      _ -> :ok
    end

    :ok
  end

  # A project that does not compile must not take the kalku with it: the
  # compiler runs in its own processes and a syntax error leaves through an
  # exit rather than an exception, so both are caught and answered.
  defp compile do
    case Mix.Task.rerun("compile", ["--no-protocol-consolidation", "--return-errors"]) do
      {:error, diagnostics} -> {:error, "prepare_failed", failure(diagnostics)}
      _ -> {:ok, module_count()}
    end
  rescue
    e -> {:error, "prepare_failed", Exception.message(e)}
  catch
    :exit, reason -> {:error, "prepare_failed", exit_message(reason)}
    _kind, value -> {:error, "prepare_failed", inspect(value)}
  end

  defp exit_message({%{__exception__: true} = e, _stack}), do: Exception.message(e)
  defp exit_message(reason), do: inspect(reason)

  # Starting applications installs the logger's handlers again, which puts
  # them back on stdout — where the protocol lives.
  defp start_apps do
    Mix.Task.rerun("app.start", [])
    Kalku.Log.to_stderr()
    :ok
  rescue
    e ->
      {:error, "prepare_failed",
       "the project's applications did not start: #{Exception.message(e)}"}
  end

  defp failure(diagnostics) when is_list(diagnostics) do
    diagnostics
    |> Enum.map(&diagnostic_line/1)
    |> Enum.reject(&(&1 == ""))
    |> Enum.join("; ")
    |> case do
      "" -> "the project does not compile"
      text -> text
    end
  end

  defp failure(_), do: "the project does not compile"

  defp diagnostic_line(%{message: message, file: file, position: position}) when is_binary(file),
    do: "#{Path.relative_to_cwd(file)}#{at(position)}: #{first_line(message)}"

  defp diagnostic_line(%{message: message}), do: first_line(message)
  defp diagnostic_line(_), do: ""

  defp at({line, column}), do: ":#{line}:#{column}"
  defp at(line) when is_integer(line) and line > 0, do: ":#{line}"
  defp at(_), do: ""

  # A compiler's first line names the problem; the rest is the drawing of
  # it, which belongs on the terminal the kalku already wrote it to.
  defp first_line(message) when is_binary(message) do
    message |> String.split("\n", parts: 2) |> hd() |> String.trim()
  end

  defp first_line(message), do: inspect(message)

  # What was compiled into the reni, which is what `prepared` reports.
  defp module_count do
    build_path()
    |> Path.join("lib/*/ebin/*.beam")
    |> Path.wildcard()
    |> length()
  end
end
