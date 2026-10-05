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

# The Rust kalku: a cargo project, built in release mode, shipped beside the others.
RKALKU     := adapters/rust/target/release/kalku-rust
RKALKU_SRC := $(shell find adapters/rust/src -name '*.rs') adapters/rust/Cargo.toml adapters/rust/Cargo.lock

# The Python kalku: one zipapp of the standard library and nothing else, run by
# the project's own interpreter, shipped beside the others.
PYKALKU     := adapters/python/dist/kalku-python
PYKALKU_SRC := $(wildcard adapters/python/src/kalku_python/*.py)
PYTHON      ?= python3

# The scripted kalku the orchestrator tests talk to, built as its own package.
FAKE     := tests/fake_kalku/fake_kalku
FAKE_SRC := $(wildcard tests/fake_kalku/*.kai) tests/fake_kalku/kai.toml

.PHONY: all build test test-kaikai test-elixir test-rust test-python test-java fmt fmt-check lint km ci check properties bench clean self-mutate dist install uninstall

all: build

build: $(BUILD)/kalku

$(BUILD)/kalku: kai.toml $(KAI_SRC)
	@mkdir -p $(BUILD)
	$(KAI) build . -o $@

test: test-kaikai test-elixir test-rust test-python test-java

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

$(RKALKU): $(RKALKU_SRC)
	cargo build --release --locked --manifest-path adapters/rust/Cargo.toml

$(PYKALKU): $(PYKALKU_SRC)
	rm -rf adapters/python/dist/app
	mkdir -p adapters/python/dist/app/kalku_python
	cp $(PYKALKU_SRC) adapters/python/dist/app/kalku_python/
	$(PYTHON) -m zipapp adapters/python/dist/app -m "kalku_python.__main__:main" -p "/usr/bin/env python3" -o $@

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

# The Python kalku, held to ruff and its own tests. A machine without pytest
# skips it, as one without Elixir or Rust skips theirs; CI has them all.
test-python:
	@if [ -f adapters/python/pyproject.toml ] && $(PYTHON) -c 'import pytest' 2>/dev/null; then \
	  cd adapters/python && $(PYTHON) -m pytest -q; \
	else echo "test-python: skipped (no adapters/python, or no pytest for $(PYTHON))"; fi

# The Java kalku, held to the compiler's own lints and its tests, on whatever
# JDK Maven finds. A machine without Maven skips it; CI runs it on every
# long-term JDK from 11 on.
test-java:
	@if [ -f adapters/java/pom.xml ] && command -v mvn >/dev/null 2>&1; then \
	  cd adapters/java && mvn -B -q verify; \
	else echo "test-java: skipped (no adapters/java/pom.xml, or no mvn)"; fi

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

check: ci test-elixir test-rust test-python test-java

# kalku on its own sources, through its own kalku: the project's claim,
# run rather than asserted. Measured by the last release, pinned in
# `.kalku-release`, never by the binary built here. One module at a time,
# because a kaikai cast rebuilds the package and runs the whole suite —
# honest and slow. Advisory: it reports and never fails a build.
SELF_MODULE  ?= kalku/core/shard.kai
SELF_LIMIT   ?= 6
# Two kalku build and test in their own copies at once, which is worth it
# on a developer's machine and not on a two-core runner.
SELF_WORKERS ?= 2

self-mutate:
	sh tools/self-mutate.sh $(SELF_MODULE) $(SELF_LIMIT) $(SELF_WORKERS) || true

# What a release ships: both binaries a user summons, side by side with
# the licences they are shipped under. Flat, so a package manager can
# install the tarball's contents without knowing this layout.
dist: build $(KKALKU) $(RKALKU) $(PYKALKU)
	@rm -rf $(DIST) && mkdir -p $(DIST)
	@cp $(BUILD)/kalku $(KKALKU) $(RKALKU) $(PYKALKU) LICENSE-MIT LICENSE-APACHE README.md $(DIST)/
	tar -czf $(TARBALL) -C $(DIST) kalku kalku-kaikai kalku-rust kalku-python LICENSE-MIT LICENSE-APACHE README.md
	@rm -f $(DIST)/kalku $(DIST)/kalku-kaikai $(DIST)/kalku-rust $(DIST)/kalku-python $(DIST)/LICENSE-* $(DIST)/README.md
	@cd $(DIST) && (command -v sha256sum >/dev/null && sha256sum $(notdir $(TARBALL)) \
	  || shasum -a 256 $(notdir $(TARBALL))) > $(notdir $(TARBALL)).sha256
	@echo "built $(TARBALL)"

install: build $(KKALKU) $(RKALKU) $(PYKALKU)
	install -d $(DESTDIR)$(PREFIX)/bin
	install -m 755 $(BUILD)/kalku $(KKALKU) $(RKALKU) $(PYKALKU) $(DESTDIR)$(PREFIX)/bin/

uninstall:
	rm -f $(DESTDIR)$(PREFIX)/bin/kalku $(DESTDIR)$(PREFIX)/bin/kalku-kaikai $(DESTDIR)$(PREFIX)/bin/kalku-rust $(DESTDIR)$(PREFIX)/bin/kalku-python

clean:
	rm -rf $(BUILD) $(DIST) .kai-cache
	rm -f $(FAKE) $(KKALKU)
	rm -rf adapters/kaikai/fixtures/reni
