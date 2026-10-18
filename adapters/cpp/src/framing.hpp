// Lines of NDJSON, each at most a maximum long: a longer one is discarded
// up to its newline without being kept, and costs only itself.
#pragma once

#include <functional>
#include <string>

namespace kalku {

class LineReader {
 public:
  enum class Got { Line, TooLong, End };
  using Source = std::function<long(char*, std::size_t)>;

  LineReader(Source read, std::size_t max) : read_(std::move(read)), max_(max) {}

  // The next line, without its newline.
  Got next(std::string& line);

 private:
  bool fill();

  Source read_;
  std::size_t max_;
  std::string buf_;
  std::size_t at_ = 0;
  bool ended_ = false;
};

}  // namespace kalku
