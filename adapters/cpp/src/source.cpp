#include "source.hpp"

#include <algorithm>

namespace kalku {

Source::Source(const std::string& text) : text_(text) {
  lines_.push_back(0);
  for (std::size_t i = 0; i < text.size(); i++) {
    if (text[i] == '\n') lines_.push_back(static_cast<long>(i) + 1);
  }
}

Position Source::position(long offset) const {
  auto after = std::upper_bound(lines_.begin(), lines_.end(), offset);
  long line = static_cast<long>(after - lines_.begin()) - 1;
  long start = lines_[static_cast<std::size_t>(line)];
  int chars = 0;
  for (long i = start; i < offset; i++) {
    // A character is every byte that does not continue one.
    if ((static_cast<unsigned char>(text_[static_cast<std::size_t>(i)]) & 0xC0) != 0x80) chars++;
  }
  return Position{static_cast<int>(line) + 1, chars + 1, offset};
}

std::string splice(const std::string& text, long from, long to, const std::string& replacement) {
  return text.substr(0, static_cast<std::size_t>(from)) + replacement + text.substr(static_cast<std::size_t>(to));
}

}  // namespace kalku
