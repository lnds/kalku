defmodule Kalku.Loop do
  @moduledoc """
  The kalku's protocol loop: one request per stdin line, one reply per
  stdout line. Nothing else may reach stdout, so Logger is pointed at
  stderr before the loop starts.
  """

  alias Kalku.{Baseline, Cast, Prepare, Protocol, Schema, Sites}

  @version Mix.Project.config()[:version]

  # How long `shutdown` waits for a cast already under way. Past this the
  # kaikai side's own deadline is the one that matters.
  @settling_ms 30_000
  @spells Schema.spells() -- ["foreign"]

  defstruct root: nil,
            reni: nil,
            env: %{},
            inline_limit: 65_536,
            prepared: false,
            reader: nil,
            casting: nil,
            waiting: []

  @doc """
  Runs the loop on stdio until `shutdown` or end of input.

  Reading happens in a fiber of its own, and a cast in another, so the
  loop can still hear while it works. `abort` arrives in the middle of
  the cast it aborts — a loop that read one line at a time and answered
  it before reading again could never receive one.
  """
  def run do
    :logger.update_handler_config(:default, :config, %{type: :standard_error})
    reader = start_reading(self())
    loop(%__MODULE__{reader: reader})
  end

  defp start_reading(owner) do
    spawn_link(fn -> read_lines(owner) end)
  end

  defp read_lines(owner) do
    case IO.binread(:stdio, :line) do
      data when is_binary(data) ->
        send(owner, {:said, String.trim_trailing(data, "\n")})
        read_lines(owner)

      _eof ->
        send(owner, :no_more)
    end
  end

  defp loop(state) do
    receive do
      {:said, line} ->
        case handle(line, state) do
          {:reply, reply, next} -> emit(reply) && loop(next)
          {:silent, next} -> loop(next)
          {:stop, reply} -> emit(reply)
        end

      {:cast_done, id, body} ->
        loop(finished(state, id, body))

      :no_more ->
        :ok
    end
  end

  # A cast in flight is finished before the kalku leaves: its result was
  # measured, and leaving without it would make the kaikai side report a
  # wekufe as crashed when it was nothing of the sort.
  defp settle(%{casting: nil, waiting: []} = state), do: state

  defp settle(%{casting: nil} = state), do: state |> start_waiting() |> settle()

  defp settle(%{casting: %{id: id}} = state) do
    receive do
      {:cast_done, ^id, body} -> state |> finished(id, body) |> settle()
    after
      @settling_ms -> %{state | casting: nil, waiting: []}
    end
  end

  # A cast that was aborted has no result to report: the abort was the
  # answer, and a `cast_done` after it would be a second one.
  defp finished(%{casting: %{id: id}} = state, id, body) do
    emit(reply("cast_done", id, body))
    start_waiting(%{state | casting: nil})
  end

  defp finished(state, _id, _body), do: state

  defp enqueue(%{casting: nil} = state, asked), do: start_cast(state, asked)
  defp enqueue(state, asked), do: %{state | waiting: state.waiting ++ [asked]}

  defp start_waiting(%{waiting: []} = state), do: state

  defp start_waiting(%{waiting: [asked | rest]} = state),
    do: start_cast(%{state | waiting: rest}, asked)

  defp start_cast(state, {id, body}) do
    %{"wekufe" => wekufe, "site" => site} = body
    casting = Cast.start(self(), id, state.root, wekufe, site, body["tests"] || [])
    %{state | casting: casting}
  end

  defp emit(line), do: IO.binwrite(:stdio, line <> "\n") == :ok

  @doc """
  Handles one request line. Returns `{:reply, line, state}` to continue or
  `{:stop, line}` after `shutdown`. Pure apart from reading the files a
  `sites` request names.
  """
  def handle(line, state) do
    case Protocol.decode(line, :request) do
      {:ok, %{type: type, id: id, body: body}} ->
        dispatch(type, id, body, state)

      {:error, kind, detail, id} ->
        {:reply, error(id || 0, "bad_request", "#{kind}: #{detail}", false), state}
    end
  end

  defp dispatch("hello", id, %{"protocol" => 1, "root" => root} = body, state) do
    {:reply, reply("ready", id, ready()),
     %{
       state
       | root: root,
         reni: body["reni"],
         env: body["env"] || %{},
         inline_limit: body["inline_limit_bytes"] || 65_536
     }}
  end

  defp dispatch("hello", id, %{"protocol" => v}, state) do
    {:reply,
     error(id, "protocol_mismatch", "kalku speaks protocol 1, kaikai side speaks #{v}", true),
     state}
  end

  defp dispatch("prepare", id, _body, %{reni: reni, env: env} = state) do
    case Prepare.run(reni, env) do
      {:ok, %{duration_ms: ms, modules: modules}} ->
        {:reply, reply("prepared", id, %{"duration_ms" => ms, "modules" => modules}),
         %{state | prepared: true}}

      {:error, code, message} ->
        {:reply, error(id, code, message, true), state}
    end
  end

  defp dispatch("baseline", id, _body, %{root: root, prepared: true} = state)
       when is_binary(root) do
    case Baseline.run(root, reni: state.reni, inline_limit_bytes: state.inline_limit) do
      {:ok, body} -> {:reply, reply("baseline_done", id, body), state}
      {:error, code, message} -> {:reply, error(id, code, message, true), state}
    end
  end

  defp dispatch("baseline", id, _body, state) do
    {:reply, error(id, "not_prepared", "`prepare` has to compile the project first", false),
     state}
  end

  # One cast at a time, in the order they arrived. A kalku casts in a
  # runtime it has exactly one of, so two at once would measure each
  # other; a request that arrives early waits rather than being refused.
  defp dispatch("cast", id, body, %{prepared: true, root: root} = state) when is_binary(root) do
    {:silent, enqueue(state, {id, body})}
  end

  defp dispatch("cast", id, _body, state) do
    {:reply, error(id, "not_prepared", "`prepare` has to compile the project first", false),
     state}
  end

  defp dispatch("abort", id, body, %{casting: %{id: cast_id} = casting} = state) do
    restored = Cast.abort(casting)

    {:reply, reply("aborted", id, %{"cast" => body["cast"] || cast_id, "restored" => restored}),
     start_waiting(%{state | casting: nil})}
  end

  # Nothing is running, so there is nothing to stop: saying so is not an
  # error, because the cast this abort chased may have finished a moment
  # before it arrived.
  defp dispatch("abort", id, body, state) do
    {:reply, reply("aborted", id, %{"cast" => body["cast"] || 0, "restored" => true}), state}
  end

  defp dispatch("sites", id, body, %{root: root} = state) when is_binary(root) do
    {:reply, reply("sites_found", id, sites(root, body)), state}
  end

  defp dispatch("shutdown", id, _body, state) do
    settle(state)
    {:stop, reply("bye", id, %{})}
  end

  defp dispatch(type, id, _body, state) do
    {:reply, error(id, "bad_request", "`#{type}` is not served by this kalku yet", false), state}
  end

  defp ready do
    %{
      "protocol" => 1,
      "language" => "elixir",
      "adapter" => @version,
      "runtime" => "Elixir #{System.version()} / OTP #{System.otp_release()}",
      "spells" => @spells,
      "capabilities" => ["cast", "per_test_coverage", "code_hash", "hot_load", "abort"]
    }
  end

  defp sites(root, %{"files" => files, "spells" => spells, "exclude_calls" => exclude}) do
    {found, skipped} =
      Enum.reduce(files, {[], []}, fn file, {found, skipped} ->
        case sites_in(root, file, spells, exclude) do
          {:ok, sites} -> {found ++ sites, skipped}
          {:skip, entry} -> {found, skipped ++ [entry]}
        end
      end)

    %{"sites" => found, "skipped" => skipped}
  end

  defp sites_in(root, file, spells, exclude) do
    with :ok <- source_file(file),
         {:ok, text} <- read(root, file),
         {:ok, sites, dropped} <- Sites.find(file, text, spells, exclude) do
      if dropped > 0,
        do:
          IO.puts(
            :stderr,
            "kalku: #{file}: #{dropped} candidate(s) dropped, wekufe would not parse"
          )

      {:ok, sites}
    else
      {:skip, reason, message} ->
        {:skip, %{"file" => file, "reason" => reason, "message" => message}}

      {:error, message} ->
        {:skip, %{"file" => file, "reason" => "parse_error", "message" => message}}
    end
  end

  # Tests and scripts are the oracle, not code under test.
  defp source_file(file) do
    cond do
      String.starts_with?(file, "test/") -> {:skip, "not_source", "test files are never mutated"}
      Path.extname(file) != ".ex" -> {:skip, "not_source", "only .ex files are searched"}
      true -> :ok
    end
  end

  defp read(root, file) do
    case File.read(Path.join(root, file)) do
      {:ok, text} -> {:ok, text}
      {:error, reason} -> {:skip, "unreadable", :file.format_error(reason) |> to_string()}
    end
  end

  defp reply(type, id, body), do: Protocol.encode(:reply, type, id, body)

  defp error(id, code, message, fatal),
    do: reply("error", id, %{"code" => code, "message" => message, "fatal" => fatal})
end
