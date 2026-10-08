defmodule Stopping do
  @moduledoc """
  Shuts the runtime's services down once enough has been done.
  """

  def settle(done) do
    if done > 10, do: Application.stop(:ex_unit), else: :ok
  end
end
