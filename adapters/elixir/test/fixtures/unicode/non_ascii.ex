defmodule Fx.Unicode do
  # ñandú: codepoints before a site shift bytes, not columns
  def hello(name), do: "¡hola #{name}!" <> tail(name == "ñandú")
  def tail(true), do: "¡olé!"
  def tail(false), do: ""
end
