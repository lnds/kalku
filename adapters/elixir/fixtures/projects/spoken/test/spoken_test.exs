defmodule SpokenTest do
  use ExUnit.Case

  test "el búho sabio llega al nivel cinco" do
    assert Spoken.level(5) == "Búho sabio"
  end

  test "el ícono 🔁 es el de los ciclos" do
    assert Spoken.icon() == "🔁"
  end
end
