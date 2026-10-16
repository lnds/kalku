defmodule Chime do
  @moduledoc "Calls a module the configuration names, which no file names."

  def ring, do: Application.fetch_env!(:reused, :ringer).ring()
end
