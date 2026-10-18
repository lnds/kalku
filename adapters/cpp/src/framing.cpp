#include "framing.hpp"

namespace kalku {

bool LineReader::fill() {
  if (ended_) return false;
  if (at_ > 0) {
    buf_.erase(0, at_);
    at_ = 0;
  }
  char chunk[65536];
  long n = read_(chunk, sizeof chunk);
  if (n <= 0) {
    ended_ = true;
    return false;
  }
  buf_.append(chunk, static_cast<std::size_t>(n));
  return true;
}

LineReader::Got LineReader::next(std::string& line) {
  bool discarding = false;
  for (;;) {
    std::size_t nl = buf_.find('\n', at_);
    if (nl != std::string::npos) {
      std::size_t len = nl - at_;
      if (!discarding && len <= max_) {
        line.assign(buf_, at_, len);
        at_ = nl + 1;
        return Got::Line;
      }
      at_ = nl + 1;
      return Got::TooLong;
    }
    if (buf_.size() - at_ > max_) {
      // Too long already: what is held is dropped, and so is the rest of
      // the line as it arrives.
      discarding = true;
      buf_.clear();
      at_ = 0;
    }
    if (!fill()) {
      if (discarding) return Got::TooLong;
      if (buf_.size() > at_) {
        line.assign(buf_, at_, std::string::npos);
        at_ = buf_.size();
        return Got::Line;
      }
      return Got::End;
    }
  }
}

}  // namespace kalku
