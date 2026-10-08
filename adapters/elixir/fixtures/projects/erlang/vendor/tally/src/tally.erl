-module(tally).
-export([count/1]).

count(Things) -> length(Things).
