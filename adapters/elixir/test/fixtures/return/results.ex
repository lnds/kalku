defmodule Results do
  use GenServer

  def total(items) do
    count = length(items)
    Enum.sum(items) / count
  end

  def name(user), do: user.first <> " " <> user.last

  defp label(%{kind: kind}) do
    case kind do
      :a -> "first"
      _ -> "other"
    end
  end

  def padded(x) when x > 0,
    do:
      x
      |> Integer.to_string()
      |> String.pad_leading(3, "0")

  def padded(_x), do: Map.new(a: 1) # a comment

  def nothing(_), do: nil
  def constant, do: 42
  def status, do: :ok
  def tag, do: :pending

  def fail(why) do
    raise ArgumentError, why
  end

  @impl true
  def init(state), do: {:ok, state}

  @impl GenServer
  @doc false
  def handle_call(:get, _from, state) do
    {:reply, state, state}
  end

  defmacro twice(x) do
    quote do: unquote(x) * 2
  end

  def call_remote(x), do: Results.Helper.run(x, [1, 2])

  def pair(x), do: {x, label(%{kind: x})}
end
