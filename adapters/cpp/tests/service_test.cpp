#include "service.hpp"

#include "test.hpp"

using namespace kalku;

namespace {

Request hello_for(const std::string& root, const std::string& options = "") {
  std::string line = "{\"type\":\"hello\",\"id\":1,\"protocol\":1,\"root\":\"" + root +
                     "\",\"reni\":\"/tmp/kalku-cpp-test-reni\",\"worker\":0,\"inline_limit_bytes\":65536,\"env\":{}" +
                     (options.empty() ? "" : ",\"options\":" + options) + "}";
  DecodeError e;
  return *decode(line, &e);
}

std::string code_of(const std::string& reply) {
  auto v = parse_json(reply);
  const Json* c = v ? v->get("code") : nullptr;
  return c ? c->text : "";
}

}  // namespace

TEST("a project with no CMakeLists.txt is refused by name") {
  Service s;
  std::atomic<bool> no{false};
  Reply r = s.handle(hello_for(kalku::testing::here("testdata/sites")), no);
  CHECK_EQ(code_of(r.line), std::string("not_a_cmake_project"));
}

TEST("a setting the kalku cannot honour is refused, named") {
  Service s;
  std::atomic<bool> no{false};
  Reply r = s.handle(hello_for(kalku::testing::here("testdata/projects/ctest"), "{\"jobs\":4}"), no);
  CHECK_EQ(code_of(r.line), std::string("bad_options"));
  CHECK(r.line.find("cpp.jobs") != std::string::npos);
}

TEST("nothing is answered before hello") {
  Service s;
  std::atomic<bool> no{false};
  DecodeError e;
  Reply r = s.handle(*decode("{\"type\":\"prepare\",\"id\":2}", &e), no);
  CHECK_EQ(code_of(r.line), std::string("not_ready"));
}

TEST("another protocol is refused") {
  Service s;
  std::atomic<bool> no{false};
  DecodeError e;
  Reply r = s.handle(*decode("{\"type\":\"hello\",\"id\":1,\"protocol\":2,\"root\":\"/\",\"reni\":\"/r\",\"worker\":0,"
                             "\"inline_limit_bytes\":1,\"env\":{}}",
                             &e),
                     no);
  CHECK_EQ(code_of(r.line), std::string("protocol_mismatch"));
}
