# kalku — build, test, and quality gates. `make ci` is what CI runs.

KAI   ?= kai
BUILD := _build

KAI_SRC := main.kai $(shell find kalku -name '*.kai') $(wildcard tests/*.kai)

.PHONY: all build test test-kaikai test-elixir fmt fmt-check lint km ci clean

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

# `kai fmt .` does not walk the package, so files go one by one.
fmt:
	@for f in $(KAI_SRC); do $(KAI) fmt $$f; done

fmt-check:
	@bad=0; for f in $(KAI_SRC); do \
	  $(KAI) fmt --check $$f >/dev/null 2>&1 || { echo "unformatted: $$f"; bad=1; }; \
	done; exit $$bad

lint:
	$(KAI) lint .

km:
	@sh tools/km-gate.sh

ci: fmt-check lint build test km

clean:
	rm -rf $(BUILD) .kai-cache
