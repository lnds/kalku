# kalku — build, test, and quality gates.
# `make ci` is CI's kaikai-side job; each language's kalku has its own job
# (`make test-elixir`, `make test-rust`). `make check` runs all of them
# locally.

KAI   ?= kai
BUILD := _build
DIST  := dist

VERSION := $(shell cat VERSION)
PREFIX  ?= /usr/local

# The platform a release tarball is named for. kaikai publishes a
# toolchain for darwin-arm64 and linux-x86_64, so those are the two
# kalku can be built for at all.
OS   := $(shell uname -s | tr 'A-Z' 'a-z')
ARCH := $(shell uname -m)
ifeq ($(ARCH),aarch64)
  ARCH := arm64
endif
PLATFORM := $(OS)-$(ARCH)
TARBALL  := $(DIST)/kalku-v$(VERSION)-$(PLATFORM).tar.gz

KAI_SRC := main.kai $(shell find kalku -name '*.kai') $(wildcard tests/*.kai)

# The kaikai kalku: the one kalku measures itself with, built as its own
# package because it is a separate binary a user summons.
KKALKU     := adapters/kaikai/bin/kalku-kaikai
KKALKU_SRC := $(wildcard adapters/kaikai/*.kai) $(wildcard adapters/kaikai/kaikai_kalku/*.kai) adapters/kaikai/kai.toml

# The scripted kalku the orchestrator tests talk to, built as its own package.
FAKE     := tests/fake_kalku/fake_kalku
FAKE_SRC := $(wildcard tests/fake_kalku/*.kai) tests/fake_kalku/kai.toml

.PHONY: all build test test-kaikai test-elixir test-rust fmt fmt-check lint km ci check properties bench clean self-mutate dist install uninstall

all: build

build: $(BUILD)/kalku

$(BUILD)/kalku: kai.toml $(KAI_SRC)
	@mkdir -p $(BUILD)
	$(KAI) build . -o $@

test: test-kaikai test-elixir test-rust

test-kaikai: $(FAKE) $(KKALKU) properties
	$(KAI) test
	$(KAI) test ./tests/fake_kalku
	cd adapters/kaikai && $(KAI) test .

# Properties, one file at a time: `kai check` in package mode does not
# find a `check` under `tests/`, and a property nobody runs is a comment.
# Named `properties` rather than `check`, which is already the target
# that runs everything including the Elixir side.
#
# Only the files that hold one. Every invocation compiles the package
# again, so asking the other twenty-eight whether they have a property
# costs a full build each to answer no.
PROPERTY_SRC := $(shell grep -l '^check "' $(wildcard tests/*_test.kai))

properties:
	@for f in $(PROPERTY_SRC); do $(KAI) check $$f || exit 1; done

# Not a gate: a benchmark fails nothing, it just says what something
# costs. Run it when a claim about speed needs a number.
bench:
	@for f in $(wildcard tests/*_test.kai); do $(KAI) bench $$f; done

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

# The Rust kalku, held to Rust's own formatter and linter. A machine without
# a Rust toolchain skips it, as one without Elixir skips the Elixir kalku;
# CI has both, so nothing reaches main unchecked.
test-rust:
	@if [ -f adapters/rust/Cargo.toml ] && command -v cargo >/dev/null 2>&1; then \
	  cd adapters/rust && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test; \
	else echo "test-rust: skipped (no adapters/rust/Cargo.toml, or no cargo)"; fi

fmt:
	$(KAI) fmt .

fmt-check:
	$(KAI) fmt --check .

lint:
	$(KAI) lint .

km:
	@sh tools/km-gate.sh

# Everything CI runs on the kaikai side, for a developer who wants it in
# one command. CI itself splits this in two, so the cheap half answers
# without waiting for the tests.
ci: fmt-check lint build test-kaikai km

check: ci test-elixir test-rust

# kalku on its own sources, through its own kalku: the project's claim,
# run rather than asserted. One module at a time, because a kaikai cast
# rebuilds the package and runs the whole suite — honest and slow.
# Advisory: it reports and never fails a build.
SELF_MODULE  ?= kalku/core/shard.kai
SELF_LIMIT   ?= 6
SELF_RENI    ?= /tmp/kalku-self/reni
# Two kalku build and test in their own copies at once, which is worth it
# on a developer's machine and not on a two-core runner.
SELF_WORKERS ?= 2

self-mutate: build $(KKALKU)
	KALKU_ROOT=. KALKU_FILES=$(SELF_MODULE) KALKU_LIMIT=$(SELF_LIMIT) \
	  KALKU_RENI=$(SELF_RENI) KALKU_WORKERS=$(SELF_WORKERS) $(BUILD)/kalku run || true

# What a release ships: both binaries a user summons, side by side with
# the licences they are shipped under. Flat, so a package manager can
# install the tarball's contents without knowing this layout.
dist: build $(KKALKU)
	@rm -rf $(DIST) && mkdir -p $(DIST)
	@cp $(BUILD)/kalku $(KKALKU) LICENSE-MIT LICENSE-APACHE README.md $(DIST)/
	tar -czf $(TARBALL) -C $(DIST) kalku kalku-kaikai LICENSE-MIT LICENSE-APACHE README.md
	@rm -f $(DIST)/kalku $(DIST)/kalku-kaikai $(DIST)/LICENSE-* $(DIST)/README.md
	@cd $(DIST) && (command -v sha256sum >/dev/null && sha256sum $(notdir $(TARBALL)) \
	  || shasum -a 256 $(notdir $(TARBALL))) > $(notdir $(TARBALL)).sha256
	@echo "built $(TARBALL)"

install: build $(KKALKU)
	install -d $(DESTDIR)$(PREFIX)/bin
	install -m 755 $(BUILD)/kalku $(KKALKU) $(DESTDIR)$(PREFIX)/bin/

uninstall:
	rm -f $(DESTDIR)$(PREFIX)/bin/kalku $(DESTDIR)$(PREFIX)/bin/kalku-kaikai

clean:
	rm -rf $(BUILD) $(DIST) .kai-cache
	rm -f $(FAKE) $(KKALKU)
	rm -rf adapters/kaikai/fixtures/reni
