defmodule Kalku.Channel do
  @moduledoc """
  The protocol's two ends, kept for the protocol.

  stdout carries the protocol, and it is also where everything else in a
  runtime writes by default: a test that prints, an application that says
  it has started, a library that inspects a value. One such line and the
  kaikai side is reading something that is not a message.

  A process writes to its group leader, and by default that is the device
  behind stdin and stdout. So the kalku keeps that device for itself, to
  read requests from and write replies to, and gives every process that had
  it standard error instead. Whatever is started afterwards inherits that,
  the project's applications and its tests among them.
  """

  @device {__MODULE__, :device}

  @doc "Keeps stdin and stdout for the protocol and sends the rest to stderr."
  def take do
    device = Process.group_leader()
    stderr = Process.whereis(:standard_error)

    for pid <- Process.list(), led_by?(pid, device), pid != device do
      Process.group_leader(pid, stderr)
    end

    # The protocol is UTF-8, and so is what the device is told to carry:
    # read and written as bytes instead, each byte of a character is taken
    # for a character of its own, encoded again on the way out and refused
    # on the way in.
    :io.setopts(device, encoding: :unicode)

    :persistent_term.put(@device, device)
    :ok
  end

  @doc "The next line of the protocol, or what the end of input looks like."
  def read_line, do: IO.read(device(), :line)

  @doc "Writes one line of the protocol; true when it was written."
  def write_line(line), do: IO.write(device(), [line, ?\n]) == :ok

  defp device, do: :persistent_term.get(@device, :stdio)

  defp led_by?(pid, device), do: Process.info(pid, :group_leader) == {:group_leader, device}
end
