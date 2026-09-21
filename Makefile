# kalku — build, test, and quality gates.
# `make ci` is CI's kaikai-side job; the Elixir kalku has its own job
# (`make test-elixir`). `make check` runs both locally.

KAI   ?= kai
BUILD := _build

KAI_SRC := main.kai $(shell find kalku -name '*.kai') $(wildcard tests/*.kai)

.PHONY: all build test test-kaikai test-elixir fmt fmt-check lint km ci check clean

all: build

build: $(BUILD)/kalku

$(BUILD)/kalku: kai.toml $(KAI_SRC)
	@mkdir -p $(BUILD)
	$(KAI) build . -o $@

test: test-kaikai test-elixir

test-kaikai:
	$(KAI) test

# The Elixir kalku joins once its mix project exists.
test-elixir:
	@if [ -f adapters/elixir/mix.exs ]; then \
	  cd adapters/elixir && mix format --check-formatted && mix test; \
	else echo "test-elixir: skipped (no adapters/elixir/mix.exs)"; fi

fmt:
	$(KAI) fmt .

fmt-check:
	$(KAI) fmt --check .

lint:
	$(KAI) lint .

km:
	@sh tools/km-gate.sh

ci: fmt-check lint build test-kaikai km

check: ci test-elixir

clean:
	rm -rf $(BUILD) .kai-cache
