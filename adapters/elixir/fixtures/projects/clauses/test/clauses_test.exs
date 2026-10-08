defmodule ClausesTest do
  use ExUnit.Case

  test "big", do: assert(Clauses.size(20) == :big)
  test "not yet big", do: assert(Clauses.size(10) == :small)

  test "positive", do: assert(Clauses.sign(5) == :positive)
  test "not yet positive", do: assert(Clauses.sign(0) == :other)

  test "withdraws", do: assert(Clauses.withdraw(5) == {:ok, 5})

  test "nothing to withdraw",
    do: assert_raise(FunctionClauseError, fn -> Clauses.withdraw(0) end)

  test "pays", do: assert(Clauses.pay(5) == {:ok, 5})
  test "nothing to pay", do: assert_raise(FunctionClauseError, fn -> Clauses.pay(0) end)
end
