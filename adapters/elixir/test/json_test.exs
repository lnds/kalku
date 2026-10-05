defmodule Kalku.JsonTest do
  use ExUnit.Case, async: true

  alias Kalku.Json

  describe "encode" do
    test "an object keeps the order it was built in, and a map sorts its keys" do
      assert Json.encode({:object, [{"b", 1}, {"a", [true, false, nil]}]}) ==
               ~s({"b":1,"a":[true,false,null]})

      assert Json.encode(%{"b" => 1, "a" => %{"d" => 2, "c" => 3}}) ==
               ~s({"a":{"c":3,"d":2},"b":1})
    end

    test "only quotes, backslashes, and control characters are escaped" do
      assert Json.encode("ñandú \"x\" \\ / 😀") == ~s("ñandú \\"x\\" \\\\ / 😀")
      assert Json.encode("\b\t\n\f\r\u0001\u001F") == ~s("\\b\\t\\n\\f\\r\\u0001\\u001F")
    end

    test "numbers are written the shortest way that reads back the same" do
      assert Json.encode([0, -7, 1.5, 0.1, 1.0e-7]) == "[0,-7,1.5,0.1,1.0e-7]"
    end

    test "a string that is not UTF-8 is refused" do
      assert_raise ArgumentError, fn -> Json.encode(<<"a", 0xFF>>) end
    end
  end

  describe "decode" do
    test "every kind of value, with whitespace between tokens" do
      assert Json.decode(~s( {"a" : [1, -2.5, 3e2, 1E-1, true, false, null], "b": {}, "c": []} )) ==
               {:ok, %{"a" => [1, -2.5, 300.0, 0.1, true, false, nil], "b" => %{}, "c" => []}}
    end

    test "escapes, including a code point written as a surrogate pair" do
      assert Json.decode(~S("\"\\\/\b\f\n\r\t\u00f1\ud83d\ude00")) ==
               {:ok, "\"\\/\b\f\n\r\tñ😀"}
    end

    test "raw UTF-8 comes through untouched" do
      assert Json.decode(~s(["ñandú", "¡olé!", "😀"])) == {:ok, ["ñandú", "¡olé!", "😀"]}
    end

    for bad <- [
          ~S(),
          ~S({),
          ~S({"a"}),
          ~S({"a":1,}),
          ~S({a:1}),
          ~S([1,]),
          ~S([1 2]),
          ~S(01),
          ~S(1.),
          ~S(.5),
          ~S(-),
          ~S(1e),
          ~S(1e999),
          ~S(tru),
          ~S(nil),
          ~S("open),
          ~S("\x"),
          ~S("\u12"),
          ~S("\ud83d"),
          ~S("\ude00"),
          ~S("\ud83dx"),
          ~S({} {}),
          "\"a\nb\"",
          <<?", 0xFF, ?">>
        ] do
      test "refuses #{inspect(bad)}" do
        assert Json.decode(unquote(bad)) == {:error, :invalid}
        assert_raise ArgumentError, fn -> Json.decode!(unquote(bad)) end
      end
    end
  end

  test "what is encoded decodes to the same value" do
    value = %{
      "text" => "línea 1\nlínea \"2\"\t\\ \u0000 😀",
      "numbers" => [0, -1, 9_007_199_254_740_993, 1.5, -0.25, 1.0e22],
      "nested" => [%{"a" => nil}, [], %{}, true, false]
    }

    assert value |> Json.encode() |> Json.decode!() == value
  end

  # Where the standard library has a JSON module, it is the second opinion.
  if Code.ensure_loaded?(JSON) do
    test "strings are escaped exactly as the built-in module escapes them" do
      for code <- Enum.concat([0..0x2FF, [0x2028, 0x2029, 0xFFFD, 0x1F600]]) do
        text = <<"a", code::utf8, "b">>
        assert Json.encode(text) == JSON.encode!(text)
        assert Json.decode(JSON.encode!(text)) == {:ok, text}
      end
    end

    test "numbers are written exactly as the built-in module writes them" do
      for n <- [0, -1, 1.0, 0.1, -2.5, 1.0e-7, 1.0e22, 123_456_789.125] do
        assert Json.encode(n) == JSON.encode!(n)
      end
    end
  end
end
