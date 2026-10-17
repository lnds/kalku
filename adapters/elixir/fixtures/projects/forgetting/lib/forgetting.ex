defmodule Forgetting do
  @moduledoc """
  Clears what a runtime was told once enough has been done.
  """

  def tidy(done) do
    if done > 10, do: Application.delete_env(:ex_unit, :repeat_until_failure), else: :ok
  end
end
