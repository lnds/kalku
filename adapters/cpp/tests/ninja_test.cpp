// Ninja's logs, as the kalku rewrites them for a copy of a build tree.

#include "ninja.hpp"

#include <cstdint>

#include "test.hpp"

namespace {

struct Hashed {
  const char* command;
  std::uint64_t hash;
};

// What Ninja 1.11.1 (log v5) and 1.13.1 (log v7) wrote in their logs for
// these commands: every length the hashes treat apart.
const Hashed MURMUR[] = {
    {":", 0x2e49d2477c6737f0ull},
    {"id", 0xb7d5d28cfa479254ull},
    {"pwd", 0xa84e122b6cefc069ull},
    {"true", 0x486a3acadd116b47ull},
    {": && touch o1", 0x11fc326dbb9da32eull},
    {"true && touch o2", 0xd6df905a18ea260eull},
    {"echo hi && touch o3", 0x9720b2aed7358168ull},
    {"echo sixteen-byte && touch o4", 0xb8bf7cc65920d5bcull},
    {"echo a seventeen-b && touch o5", 0xab4081fef13257fbull},
    {"echo this one has thirty-three by && touch o6", 0x551406cf47fe71ddull},
    {"echo forty-eight bytes long command for the hash! && touch o7", 0x6e69f69e8b7daa21ull},
    {"echo this command is longer than forty-eight bytes for sure, yes && touch o8", 0x1cc4f6492249db5aull},
    {"echo a command that runs past ninety-six bytes so that the hash takes the unrolled loop twice over, ok && touch "
     "o9",
     0xf883c2c6a59ac344ull},
};

const Hashed RAPID[] = {
    {":", 0xa2da22321247804bull},
    {"id", 0x67e4fdc0567284ddull},
    {"pwd", 0xe3992a528465d258ull},
    {"true", 0x90a52a5dca2ed61ull},
    {": && touch o1", 0xcfa1a74ecdeb8856ull},
    {"true && touch o2", 0x32ed5739dad4250dull},
    {"echo hi && touch o3", 0x7fbb3d5d93edb5c2ull},
    {"echo sixteen-byte && touch o4", 0xad97fe86af284427ull},
    {"echo a seventeen-b && touch o5", 0x73f3d5b462e878c6ull},
    {"echo this one has thirty-three by && touch o6", 0x3392302ec97acf1bull},
    {"echo forty-eight bytes long command for the hash! && touch o7", 0xa5cb42eb6cc5cc31ull},
    {"echo this command is longer than forty-eight bytes for sure, yes && touch o8", 0xcc130dc5f29912c2ull},
    {"echo a command that runs past ninety-six bytes so that the hash takes the unrolled loop twice over, ok && touch "
     "o9",
     0xb9879a1898b5630dull},
};

std::string word(std::uint32_t v) {
  std::string out;
  for (int i = 0; i < 4; i++) out += static_cast<char>(v >> (8 * i) & 0xff);
  return out;
}

std::string path_record(const std::string& path, int id) {
  std::size_t padding = (4 - path.size() % 4) % 4;
  return word(static_cast<std::uint32_t>(path.size() + padding + 4)) + path + std::string(padding, '\0') +
         word(~static_cast<std::uint32_t>(id));
}

std::string deps_record(int out, std::uint64_t mtime, const std::vector<int>& ins) {
  std::string body = word(static_cast<std::uint32_t>(out)) + word(static_cast<std::uint32_t>(mtime)) +
                     word(static_cast<std::uint32_t>(mtime >> 32));
  for (int i : ins) body += word(static_cast<std::uint32_t>(i));
  return word(static_cast<std::uint32_t>(body.size()) | 0x80000000u) + body;
}

const std::string HEADER = std::string("# ninjadeps\n") + word(4);

}  // namespace

TEST("a command is hashed as the Ninja that wrote the log hashes it") {
  for (const auto& h : MURMUR) {
    CHECK_EQ(kalku::ninja_hash(5, h.command).value_or(0), h.hash);
    CHECK_EQ(kalku::ninja_hash(6, h.command).value_or(0), h.hash);
  }
  for (const auto& h : RAPID) CHECK_EQ(kalku::ninja_hash(7, h.command).value_or(0), h.hash);
  CHECK(!kalku::ninja_hash(4, "true"));
  CHECK(!kalku::ninja_hash(8, "true"));
  CHECK_EQ(kalku::ninja_log_version("# ninja log v7\n1\t2\t3\tx\tab\n"), 7);
  CHECK_EQ(kalku::ninja_log_version("garbage"), 0);
}

TEST("a log entry takes its command's new hash, and one with no known hash is left out") {
  std::string log =
      "# ninja log v7\n"
      "1\t9\t1700000000000000000\tCMakeFiles/a.dir/a.c.o\tabc\n"
      "2\t8\t1700000000000000001\tCMakeFiles/b.dir/b.c.o\tdef\n"
      "3\t7\t1700000000000000002\tlinked\tabc\n";
  int dropped = 0;
  std::string out = kalku::rebase_ninja_log(log, {{0xabc, 0x123456789abcdefull}}, &dropped);
  CHECK_EQ(dropped, 1);
  CHECK_EQ(out, std::string("# ninja log v7\n"
                            "1\t9\t1700000000000000000\tCMakeFiles/a.dir/a.c.o\t123456789abcdef\n"
                            "3\t7\t1700000000000000002\tlinked\t123456789abcdef\n"));
}

TEST("a header in the deps log is named by the copy's path, ids and mtimes kept") {
  kalku::Rewrites rw = {{"/r/work/base", "/r/work/12"}, {"/r/build/base", "/r/build/12"}};
  std::string deps = HEADER + path_record("CMakeFiles/a.dir/a.c.o", 0) + path_record("/r/work/base/a.c", 1) +
                     path_record("/r/work/base/include/a.h", 2) + path_record("/usr/include/stdio.h", 3) +
                     deps_record(0, 0x123456789ull, {1, 2, 3});
  auto out = kalku::rebase_ninja_deps(deps, rw, {"/r/work/base", "/r/build/base"});
  CHECK(out.has_value());
  if (out) {
    CHECK_EQ(*out, HEADER + path_record("CMakeFiles/a.dir/a.c.o", 0) + path_record("/r/work/12/a.c", 1) +
                       path_record("/r/work/12/include/a.h", 2) + path_record("/usr/include/stdio.h", 3) +
                       deps_record(0, 0x123456789ull, {1, 2, 3}));
  }
}

TEST("a deps log that would still name the base's header is no copy") {
  // The base as a path the rewrites do not know, as through another link.
  kalku::Rewrites rw = {{"/private/r/work/base", "/private/r/work/1"}};
  std::string deps = HEADER + path_record("/r/work/base/include/a.h", 0) + deps_record(0, 1, {0});
  CHECK(!kalku::rebase_ninja_deps(deps, rw, {"/private/r/work/base", "/r/work/base"}));
  CHECK(!kalku::rebase_ninja_deps("# ninjadeps\n" + word(3), rw, {}));
  CHECK(!kalku::rebase_ninja_deps("not a deps log", rw, {}));
}
