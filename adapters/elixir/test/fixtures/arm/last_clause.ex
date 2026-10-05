defmodule Fx.LastClause do
  # the last clause of a module is followed by nothing but its `end`
  def plain(1), do: :one
  def plain(_), do: :other
end

defmodule Fx.LastClause.Trailed do
  def trailed(1), do: :one
  def trailed(_), do: :other

  # a comment and a blank line are not part of the clause

end

defmodule Fx.LastClause.Text do
  def text(1), do: "one"

  def text(_), do: "a string
# that ends on a line that reads as a comment"
end

defmodule Fx.LastClause.Piped do
  def piped(1), do: :one

  def piped(n),
    do:
      n
      |> to_string()
end
