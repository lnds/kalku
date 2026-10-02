#!/bin/sh
# kalku on its own sources, measured by the last release, never by the
# binary built from the same commit: a bug in the kalku under test could
# otherwise hide its own survivors.
#
#   tools/self-mutate.sh [module] [limit] [workers]
#
# The release is the one `.kalku-release` pins, bumped deliberately. The
# run happens in a copy of the tree, which is where `kalku init` writes its
# configuration, so the working tree is never touched.
set -eu

module=${1:-kalku/core/shard.kai}
limit=${2:-6}
workers=${3:-2}

root=$(cd "$(dirname "$0")/.." && pwd)
version=$(cat "$root/.kalku-release")
work=${SELF_DIR:-${TMPDIR:-/tmp}/kalku-self}
release="$work/release/$version"

os=$(uname -s | tr 'A-Z' 'a-z')
arch=$(uname -m)
[ "$arch" = aarch64 ] && arch=arm64
tarball="kalku-v$version-$os-$arch.tar.gz"

if [ ! -x "$release/kalku" ]; then
  mkdir -p "$release"
  curl -fsSL "https://github.com/lnds/kalku/releases/download/v$version/$tarball" \
    -o "$release/$tarball"
  tar -xzf "$release/$tarball" -C "$release"
fi

tree="$work/tree"
rm -rf "$tree"
mkdir -p "$tree"
rsync -a --exclude .git --exclude _build --exclude .kai-cache --exclude dist \
  --exclude target --exclude deps --exclude '*.beam' "$root/" "$tree/"

cd "$tree"
PATH="$release:$PATH"
export PATH
kalku init --yes >&2
sed -i.bak "s/^workers = .*/workers = $workers/" .kalku.toml && rm -f .kalku.toml.bak
echo "kalku $version measuring $module" >&2
kalku run "$module" --limit "$limit"
