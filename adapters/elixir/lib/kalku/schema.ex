defmodule Kalku.Schema do
  @moduledoc """
  The kalku protocol's field tables, in canonical order (docs/protocol.md).
  One table drives both validation on decode and field order on encode.
  """

  @spells ~w(arm compare connect negate literal call await supervise foreign)
  @outcomes ~w(killed survived timeout no_coverage compile_error crashed nondeterministic equivalent)

  @enums %{
    spell: @spells,
    outcome: @outcomes,
    cast_outcome: ~w(killed survived compile_error equivalent),
    reload: ~w(module dependents),
    baseline_status: ~w(green red)
  }

  @shapes %{
    position: [line: :int, col: :int, byte: :int],
    span: [start: {:shape, :position}, end: {:opt, {:shape, :position}}],
    site: [
      site_id: :string,
      file: :string,
      enclosing: {:opt, :string},
      ordinal: {:opt, :int},
      span: {:shape, :span},
      spell: {:enum, :spell},
      original: {:opt, :string},
      replacement: :string,
      reload: {:enum, :reload}
    ],
    test_timing: [test: :string, file: :string, duration_ms: :int],
    coverage: [file: :string, line: :int, tests: {:list, :string}],
    failure: [test: :string, message: :string],
    skipped: [file: :string, reason: :string, message: :string],
    delegated_outcome: [
      file: :string,
      enclosing: {:opt, :string},
      span: {:shape, :span},
      spell: {:enum, :spell},
      tool_mutator: :string,
      original: {:opt, :string},
      replacement: {:opt, :string},
      outcome: {:enum, :outcome},
      tool_status: :string,
      killed_by: {:opt, :string}
    ]
  }

  @requests %{
    "hello" => [
      protocol: :int,
      root: :string,
      reni: :string,
      worker: :int,
      inline_limit_bytes: :int,
      env: :strmap
    ],
    "prepare" => [],
    "baseline" => [],
    "sites" => [
      files: {:list, :string},
      spells: {:list, {:enum, :spell}},
      exclude_calls: {:list, :string}
    ],
    "cast" => [wekufe: :string, site: {:shape, :site}, tests: {:list, :string}],
    "abort" => [cast: :int],
    "reset" => [],
    "reload" => [files: {:list, :string}],
    "delegate" => [scope: :scope, spells: {:opt, {:list, {:enum, :spell}}}],
    "shutdown" => []
  }

  @replies %{
    "ready" => [
      protocol: :int,
      language: :string,
      adapter: :string,
      runtime: :string,
      spells: {:list, {:enum, :spell}},
      capabilities: {:list, :string}
    ],
    "prepared" => [duration_ms: :int, modules: :int],
    "baseline_done" => [
      status: {:enum, :baseline_status},
      duration_ms: :int,
      tests: {:list, {:shape, :test_timing}},
      coverage: {:opt, {:list, {:shape, :coverage}}},
      coverage_path: {:opt, :string},
      failures: {:list, {:shape, :failure}},
      differences: {:opt, {:list, :string}}
    ],
    "progress" => [done: :int, total: :int],
    "sites_found" => [sites: {:list, {:shape, :site}}, skipped: {:list, {:shape, :skipped}}],
    "cast_done" => [
      wekufe: :string,
      outcome: {:enum, :cast_outcome},
      killed_by: {:opt, :string},
      message: {:opt, :string},
      code_hash: {:opt, :string},
      duration_ms: :int,
      dirty: :bool
    ],
    "aborted" => [cast: :int, restored: :bool],
    "reset_done" => [clean: :bool, duration_ms: :int],
    "reloaded" => [modules: {:list, :string}, dependents: {:list, :string}, duration_ms: :int],
    "delegated" => [
      tool: :string,
      tool_version: :string,
      outcomes: {:opt, {:list, {:shape, :delegated_outcome}}},
      outcomes_path: {:opt, :string},
      duration_ms: :int
    ],
    "bye" => [],
    "error" => [code: :string, message: :string, fatal: :bool]
  }

  @doc "The spells the protocol knows, in canonical order."
  def spells, do: @spells

  @doc "Field table of a message type for a direction (`:request` or `:reply`), or `nil`."
  def message(:request, type), do: Map.get(@requests, type)
  def message(:reply, type), do: Map.get(@replies, type)

  @doc "Field table of a shared shape."
  def shape(name), do: Map.fetch!(@shapes, name)

  @doc "Values of a closed set."
  def enum(name), do: Map.fetch!(@enums, name)
end
