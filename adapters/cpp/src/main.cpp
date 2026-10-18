// kalku-cpp is the C and C++ kalku: it speaks the kalku protocol on stdio, finds
// sites with Clang's own parser, and judges each wekufe with the project's
// own build and CTest, in a copy of the project kept in the reni.

#include <unistd.h>

#include <iostream>

#include "process.hpp"
#include "serve.hpp"
#include "version.hpp"

int main(int argc, char**) {
  if (argc > 1) {
    std::cerr << "kalku-cpp " << kalku::VERSION
              << ": summoned by kalku, which speaks to it on stdin; it takes no arguments\n";
    return 2;
  }
  kalku::Service service;
  auto in = [](char* buf, std::size_t n) -> long { return static_cast<long>(read(0, buf, n)); };
  auto out = [](const std::string& line) {
    std::size_t done = 0;
    while (done < line.size()) {
      ssize_t n = write(1, line.data() + done, line.size() - done);
      if (n <= 0) return false;
      done += static_cast<std::size_t>(n);
    }
    return true;
  };
  int code = kalku::serve(in, out, service, [] { kalku::leave(0); });
  return code;
}
