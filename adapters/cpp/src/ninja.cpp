#include "ninja.hpp"

#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <sstream>

namespace kalku {

namespace {

std::uint64_t read64(const unsigned char* p) {
  std::uint64_t v = 0;
  for (int i = 7; i >= 0; i--) v = v << 8 | p[i];
  return v;
}

std::uint64_t read32(const unsigned char* p) {
  return static_cast<std::uint64_t>(p[0]) | static_cast<std::uint64_t>(p[1]) << 8 |
         static_cast<std::uint64_t>(p[2]) << 16 | static_cast<std::uint64_t>(p[3]) << 24;
}

std::uint64_t murmur64a(const std::string& s) {
  const std::uint64_t m = 0xc6a4a7935bd1e995ull;
  const int r = 47;
  std::size_t len = s.size();
  std::uint64_t h = 0xDECAFBADDECAFBADull ^ (len * m);
  const auto* data = reinterpret_cast<const unsigned char*>(s.data());
  while (len >= 8) {
    std::uint64_t k = read64(data);
    k *= m;
    k ^= k >> r;
    k *= m;
    h ^= k;
    h *= m;
    data += 8;
    len -= 8;
  }
  if (len > 0) {
    for (std::size_t i = len; i-- > 0;) h ^= static_cast<std::uint64_t>(data[i]) << (8 * i);
    h *= m;
  }
  h ^= h >> r;
  h *= m;
  h ^= h >> r;
  return h;
}

// The 128-bit product of two words: the low word in `a`, the high in `b`.
void mum(std::uint64_t* a, std::uint64_t* b) {
  std::uint64_t ha = *a >> 32, hb = *b >> 32, la = *a & 0xffffffffu, lb = *b & 0xffffffffu;
  std::uint64_t rh = ha * hb, rm0 = ha * lb, rm1 = hb * la, rl = la * lb;
  std::uint64_t t = rl + (rm0 << 32);
  std::uint64_t carry = t < rl;
  std::uint64_t lo = t + (rm1 << 32);
  carry += lo < t;
  *a = lo;
  *b = rh + (rm0 >> 32) + (rm1 >> 32) + carry;
}

std::uint64_t mix(std::uint64_t a, std::uint64_t b) {
  mum(&a, &b);
  return a ^ b;
}

// rapidhash, by Nicolas De Carli, with the seed and secrets Ninja uses.
std::uint64_t rapidhash(const std::string& s) {
  const std::uint64_t secret[3] = {0x2d358dccaa6c78a5ull, 0x8bb84b93962eacc9ull, 0x4b33a62ed433d4a3ull};
  const auto* p = reinterpret_cast<const unsigned char*>(s.data());
  const std::size_t len = s.size();
  std::uint64_t seed = 0xbdd89aa982704029ull;
  seed ^= mix(seed ^ secret[0], secret[1]) ^ len;
  std::uint64_t a = 0, b = 0;
  if (len <= 16) {
    if (len >= 4) {
      const unsigned char* last = p + len - 4;
      a = read32(p) << 32 | read32(last);
      const std::size_t delta = (len & 24) >> (len >> 3);
      b = read32(p + delta) << 32 | read32(last - delta);
    } else if (len > 0) {
      a = static_cast<std::uint64_t>(p[0]) << 56 | static_cast<std::uint64_t>(p[len >> 1]) << 32 | p[len - 1];
    }
  } else {
    std::size_t i = len;
    if (i > 48) {
      std::uint64_t see1 = seed, see2 = seed;
      do {
        seed = mix(read64(p) ^ secret[0], read64(p + 8) ^ seed);
        see1 = mix(read64(p + 16) ^ secret[1], read64(p + 24) ^ see1);
        see2 = mix(read64(p + 32) ^ secret[2], read64(p + 40) ^ see2);
        p += 48;
        i -= 48;
      } while (i >= 48);
      seed ^= see1 ^ see2;
    }
    if (i > 16) {
      seed = mix(read64(p) ^ secret[2], read64(p + 8) ^ seed ^ secret[1]);
      if (i > 32) seed = mix(read64(p + 16) ^ secret[2], read64(p + 24) ^ seed);
    }
    a = read64(p + i - 16);
    b = read64(p + i - 8);
  }
  a ^= secret[1];
  b ^= seed;
  mum(&a, &b);
  return mix(a ^ secret[0] ^ len, b ^ secret[1]);
}

std::string rewritten(std::string s, const Rewrites& rw) {
  for (const auto& r : rw) {
    std::size_t at = 0;
    while ((at = s.find(r.first, at)) != std::string::npos) {
      s.replace(at, r.first.size(), r.second);
      at += r.second.size();
    }
  }
  return s;
}

void put32(std::string& out, std::uint32_t v) {
  for (int i = 0; i < 4; i++) out += static_cast<char>(v >> (8 * i) & 0xff);
}

}  // namespace

std::optional<std::uint64_t> ninja_hash(int version, const std::string& command) {
  if (version == 5 || version == 6) return murmur64a(command);
  if (version == 7) return rapidhash(command);
  return std::nullopt;
}

int ninja_log_version(const std::string& log) {
  int v = 0;
  return std::sscanf(log.c_str(), "# ninja log v%d\n", &v) == 1 ? v : 0;
}

std::string rebase_ninja_log(const std::string& log, const std::map<std::uint64_t, std::uint64_t>& rehash,
                             int* dropped) {
  std::stringstream in(log);
  std::string line, out;
  std::getline(in, line);
  out = line + "\n";
  while (std::getline(in, line)) {
    auto tab = line.rfind('\t');
    std::uint64_t old = 0;
    auto it = rehash.end();
    if (tab != std::string::npos && std::sscanf(line.c_str() + tab + 1, "%" SCNx64, &old) == 1) it = rehash.find(old);
    if (it == rehash.end()) {
      (*dropped)++;
      continue;
    }
    char hex[17];
    std::snprintf(hex, sizeof hex, "%" PRIx64, it->second);
    out += line.substr(0, tab + 1) + hex + "\n";
  }
  return out;
}

std::optional<std::string> rebase_ninja_deps(const std::string& deps, const Rewrites& rw,
                                             const std::vector<std::string>& old_tree) {
  const std::string signature = "# ninjadeps\n";
  const std::size_t max_record = (1u << 19) - 1;
  const auto* p = reinterpret_cast<const unsigned char*>(deps.data());
  if (deps.size() < signature.size() + 4 || deps.compare(0, signature.size(), signature) != 0 ||
      read32(p + signature.size()) != 4) {
    return std::nullopt;
  }
  std::string out = deps.substr(0, signature.size() + 4);
  std::size_t at = signature.size() + 4;
  // A record cut short ends the log, as it does when Ninja reads it.
  while (at + 4 <= deps.size()) {
    auto size = static_cast<std::uint32_t>(read32(p + at));
    bool is_deps = (size & 0x80000000u) != 0;
    size &= 0x7fffffffu;
    if (size > max_record || at + 4 + size > deps.size()) break;
    if (is_deps) {
      out.append(deps, at, 4 + size);
    } else {
      if (size < 4) return std::nullopt;
      std::string path = deps.substr(at + 4, size - 4);
      while (!path.empty() && path.back() == '\0') path.pop_back();
      path = rewritten(path, rw);
      for (const auto& t : old_tree) {
        if (path.find(t) != std::string::npos) return std::nullopt;
      }
      std::size_t padding = (4 - path.size() % 4) % 4;
      std::size_t record = path.size() + padding + 4;
      if (path.empty() || record > max_record) return std::nullopt;
      put32(out, static_cast<std::uint32_t>(record));
      out += path;
      out.append(padding, '\0');
      out.append(deps, at + size, 4);
    }
    at += 4 + size;
  }
  return out;
}

}  // namespace kalku
