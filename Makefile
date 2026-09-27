# kalku — build, test, and quality gates.
# `make ci` is CI's kaikai-side job; the Elixir kalku has its own job
# (`make test-elixir`). `make check` runs both locally.

KAI   ?= kai
BUILD := _build

KAI_SRC := main.kai $(shell find kalku -name '*.kai') $(wildcard tests/*.kai)

# The kaikai kalku: the one kalku measures itself with, built as its own
# package because it is a separate binary a user summons.
KKALKU     := adapters/kaikai/bin/kalku-kaikai
KKALKU_SRC := $(wildcard adapters/kaikai/*.kai) $(wildcard adapters/kaikai/kaikai_kalku/*.kai) adapters/kaikai/kai.toml

# The scripted kalku the orchestrator tests talk to, built as its own package.
FAKE     := tests/fake_kalku/fake_kalku
FAKE_SRC := $(wildcard tests/fake_kalku/*.kai) tests/fake_kalku/kai.toml

.PHONY: all build test test-kaikai test-elixir fmt fmt-check lint km ci check clean self-mutate

all: build

build: $(BUILD)/kalku

$(BUILD)/kalku: kai.toml $(KAI_SRC)
	@mkdir -p $(BUILD)
	$(KAI) build . -o $@

test: test-kaikai test-elixir

test-kaikai: $(FAKE) $(KKALKU)
	$(KAI) test
	$(KAI) test ./tests/fake_kalku
	cd adapters/kaikai && $(KAI) test .

$(KKALKU): $(KKALKU_SRC) $(shell find kalku -name '*.kai')
	@mkdir -p $(dir $@)
	$(KAI) build ./adapters/kaikai -o $@

$(FAKE): $(FAKE_SRC)
	$(KAI) build ./tests/fake_kalku

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

# kalku on its own sources. Until the CLI exists this is kaikai's own
# `kai mutate`, which is the same engine the kaikai kalku drives — so the
# survivors are the same ones, found the long way round. Advisory: it
# reports and never fails a build.
self-mutate:
	$(KAI) mutate --limit 20 --module kalku/core/score.kai || true

clean:
	rm -rf $(BUILD) .kai-cache
	rm -f $(FAKE) $(KKALKU)
	rm -rf adapters/kaikai/fixtures/reni
