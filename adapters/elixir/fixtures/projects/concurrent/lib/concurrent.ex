defmodule Concurrent do
  @moduledoc """
  A handful of concurrency decisions, each one a place a suite can fail
  to look: a deadline, a supervision strategy, a link.
  """

  use Supervisor

  def start_link(opts), do: Supervisor.start_link(__MODULE__, :ok, opts)

  @impl true
  def init(:ok) do
    children = [{Concurrent.Waiter, []}]
    Supervisor.init(children, strategy: :one_for_one)
  end

  @doc "Waits for a reply, and gives up after a while."
  def wait_for(pid) do
    send(pid, {:ping, self()})

    receive do
      {:pong, value} -> {:ok, value}
    after
      50 -> {:error, :timeout}
    end
  end

  @doc "Asks the waiter, with a deadline of its own."
  def ask(server), do: GenServer.call(server, :value, 100)

  @doc "Starts a helper whose death this process wants to hear about."
  def helper_of(fun) do
    pid = spawn(fun)
    Process.link(pid)
    pid
  end
end

defmodule Concurrent.Waiter do
  @moduledoc "A server that answers, eventually."

  use GenServer

  def start_link(_opts), do: GenServer.start_link(__MODULE__, 0, name: __MODULE__)

  @impl true
  def init(n), do: {:ok, n}

  @impl true
  def handle_call(:value, _from, n), do: {:reply, n, n}

  @impl true
  def handle_info({:ping, from}, n) do
    send(from, {:pong, n})
    {:noreply, n}
  end
end
