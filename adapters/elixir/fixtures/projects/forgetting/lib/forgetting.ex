defmodule Forgetting do
  @moduledoc """
  Clears what a runtime was told once enough has been done.
  """

  def tidy(done) do
    if done > 10, do: Application.delete_env(:ex_unit, :timeout), else: :ok
  end
end
