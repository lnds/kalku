defmodule Kalku.Cast.Beams do
  @moduledoc """
  A wekufe's modules, where the runtime looks for a module's file.

  A wekufe is compiled and loaded in memory, and that is enough for code
  that calls it. It is not enough for code that asks for a module's object
  code: that is read from the code path, where the original is. A mocking
  library builds the copy it keeps of a module from there, so a test that
  drove a mock put the original back behind it, and every test after it in
  the same cast was run against the original.

  So for as long as its tests run, the wekufe's modules are also written to
  a directory of their own at the front of the code path. The directory is
  in the reni, under the build path, and carries this runtime's process id:
  the originals are never written over, another kalku on the same build
  path never sees it, and one left behind by a kalku that was killed is in
  no code path at all.
  """

  @doc "Puts these modules ahead of their originals on the code path."
  def put(compiled) do
    clear()
    File.mkdir_p!(dir())
    for {module, binary} <- compiled, do: File.write!(Path.join(dir(), "#{module}.beam"), binary)
    :code.add_patha(String.to_charlist(dir()))
    :ok
  end

  @doc "Takes them away again, so the code path has only the originals."
  def clear do
    :code.del_path(String.to_charlist(dir()))
    File.rm_rf(dir())
    :ok
  end

  defp dir, do: Path.join([Mix.Project.build_path(), "kalku", "cast-#{:os.getpid()}"])
end
