defmodule Kalku.ReuseTest do
  use ExUnit.Case, async: false

  import Kalku.KalkuCase

  # A baseline that runs only what changed: each test module is given a
  # fingerprint of what its tests run, and a module the kaikai side offers
  # back with the fingerprint it has now is not run again.
  describe "a baseline offered what it measured before" do
    setup :a_reni

    test "gives each module a fingerprint, and one reaching past what it names none",
         %{reni: reni} do
      dir = a_copy()
      done = baseline(dir, reni, nil)

      assert done["status"] == "green"
      prints = prints_of(done)
      assert prints["test/door_test.exs:4"] == prints["test/door_test.exs:8"]
      assert prints["test/bell_test.exs:4"] != prints["test/door_test.exs:4"]

      # The chime reaches a module only the configuration names, which no
      # fingerprint can follow: it always runs.
      assert Map.has_key?(prints, "test/chime_test.exs:4")
      assert prints["test/chime_test.exs:4"] == nil
      refute Map.has_key?(done, "reused")
    end

    test "does not run the modules whose fingerprint is unchanged", %{reni: reni} do
      dir = a_copy()
      first = baseline(dir, reni, nil)
      done = baseline(dir, reni, offer(first))

      assert done["status"] == "green"

      assert Enum.sort(done["reused"]) ==
               ["test/bell_test.exs:4", "test/door_test.exs:4", "test/door_test.exs:8"]

      assert Enum.map(done["tests"], & &1["test"]) == ["test/chime_test.exs:4"]
      assert Enum.all?(done["coverage"], &(&1["tests"] == ["test/chime_test.exs:4"]))
    end

    # The door's own file did not change; the macro it expands did.
    test "runs a module again when a macro it expands changes", %{reni: reni} do
      dir = a_copy()
      first = baseline(dir, reni, nil)

      greeter = Path.join(dir, "lib/greeter.ex")

      File.write!(
        greeter,
        String.replace(File.read!(greeter), "unquote(name)", "unquote(name) <> \"\"")
      )

      done = baseline(dir, reni, offer(first))

      assert done["status"] == "green"
      assert done["reused"] == ["test/bell_test.exs:4"]

      ran = Enum.map(done["tests"], & &1["test"]) |> Enum.sort()
      assert ran == ["test/chime_test.exs:4", "test/door_test.exs:4", "test/door_test.exs:8"]
    end

    test "gives no fingerprint to a module with a failing test, and does to the rest",
         %{reni: reni} do
      dir = a_copy()
      greeter = Path.join(dir, "lib/greeter.ex")
      File.write!(greeter, String.replace(File.read!(greeter), "hello, ", "hi, "))

      done = baseline(dir, reni, nil)

      assert done["status"] == "red"
      prints = prints_of(done)
      assert prints["test/door_test.exs:4"] == nil
      assert prints["test/door_test.exs:8"] == nil
      assert prints["test/bell_test.exs:4"] != nil
    end

    # A module is run whole or not at all.
    test "runs a module whose tests were not all offered", %{reni: reni} do
      dir = a_copy()
      first = baseline(dir, reni, nil)
      offered = Enum.reject(offer(first), &(&1["test"] == "test/door_test.exs:8"))

      done = baseline(dir, reni, offered)

      assert done["reused"] == ["test/bell_test.exs:4"]
    end

    test "runs a module offered with another fingerprint", %{reni: reni} do
      dir = a_copy()
      first = baseline(dir, reni, nil)
      offered = Enum.map(offer(first), &%{&1 | "fingerprint" => "another"})

      done = baseline(dir, reni, offered)

      refute Map.has_key?(done, "reused")
      assert length(done["tests"]) == 4
    end

    test "with nothing left to run is green, and says its tests reached nothing",
         %{reni: reni} do
      dir = a_copy()
      File.rm!(Path.join(dir, "test/chime_test.exs"))
      first = baseline(dir, reni, nil)

      done = baseline(dir, reni, offer(first))

      assert done["status"] == "green"
      assert done["tests"] == []
      assert done["coverage"] == []
      assert length(done["reused"]) == 3
    end
  end

  # A baseline asked about a few files runs the test modules that may reach
  # them and defers the rest, so a run over one file does not wait for the
  # whole suite; a later baseline asked for exactly those runs them.
  describe "a baseline scoped to some files" do
    setup :a_reni

    test "runs the modules that name a file, and defers the others", %{reni: reni} do
      done = asked(a_copy(), reni, %{"for_files" => ["lib/bell.ex"]})

      assert done["status"] == "green"
      assert Enum.map(done["tests"], & &1["test"]) == ["test/bell_test.exs:4"]

      assert Enum.sort(done["deferred"]) ==
               ["test/chime_test.exs:4", "test/door_test.exs:4", "test/door_test.exs:8"]

      assert Enum.all?(done["coverage"], &(&1["tests"] == ["test/bell_test.exs:4"]))
    end

    test "follows what a test file names down to the file asked about", %{reni: reni} do
      done = asked(a_copy(), reni, %{"for_files" => ["lib/greeter.ex"]})

      assert Enum.sort(Enum.map(done["tests"], & &1["test"])) ==
               ["test/door_test.exs:4", "test/door_test.exs:8"]

      assert Enum.sort(done["deferred"]) == ["test/bell_test.exs:4", "test/chime_test.exs:4"]
    end

    # Nothing the compiler can see reaches the ringer: the chime calls it
    # through the configuration. Running no test would judge nothing.
    test "runs the whole suite when no module would be run", %{reni: reni} do
      done = asked(a_copy(), reni, %{"for_files" => ["lib/ringer.ex"]})

      assert length(done["tests"]) == 4
      refute Map.has_key?(done, "deferred")
    end

    test "runs exactly the tests it is asked for, and defers nothing", %{reni: reni} do
      dir = a_copy()
      first = asked(dir, reni, %{"for_files" => ["lib/bell.ex"]})
      done = asked(dir, reni, %{"only" => first["deferred"]})

      assert Enum.sort(Enum.map(done["tests"], & &1["test"])) == Enum.sort(first["deferred"])
      refute Map.has_key?(done, "deferred")
      refute Enum.any?(done["coverage"], &("test/bell_test.exs:4" in &1["tests"]))
    end
  end

  defp asked(dir, reni, body) do
    line = Kalku.Json.encode(Map.merge(%{"type" => "baseline", "id" => 3}, body))

    lines =
      drive_in(dir, reni, [hello(reni), request("prepare", 2), line, request("shutdown", 4)])

    reply(lines, "baseline_done")
  end

  defp baseline(dir, reni, reuse) do
    asked =
      case reuse do
        nil -> request("baseline", 3)
        reuse -> Kalku.Json.encode(%{"type" => "baseline", "id" => 3, "reuse" => reuse})
      end

    lines =
      drive_in(dir, reni, [hello(reni), request("prepare", 2), asked, request("shutdown", 4)])

    reply(lines, "baseline_done")
  end

  defp prints_of(done), do: Map.new(done["tests"], &{&1["test"], &1["fingerprint"]})

  defp offer(done) do
    for t <- done["tests"],
        t["fingerprint"] != nil,
        do: %{"test" => t["test"], "fingerprint" => t["fingerprint"]}
  end

  # The fixture somewhere it can be changed, depending on this kalku.
  defp a_copy do
    dir = Path.join(System.tmp_dir!(), "kalku-reused-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf!(dir) end)
    File.cp_r!(project("reused"), dir)
    File.rm_rf!(Path.join(dir, "_build"))

    mix = Path.join(dir, "mix.exs")
    adapter = Path.expand("..", __DIR__)
    File.write!(mix, String.replace(File.read!(mix), ~s("../../.."), inspect(adapter)))
    dir
  end
end
