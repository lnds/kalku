import Config

config :attached, :logger, [
  {:handler, :attached_from_config, :logger_std_h, %{level: :none}}
]
