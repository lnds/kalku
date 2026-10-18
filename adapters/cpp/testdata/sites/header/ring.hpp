#pragma once

#include <cstddef>

// A wekufe in a header recompiles everything that includes it.
template <typename T, std::size_t N>
class Ring {
 public:
  bool push(const T& v) {
    if (count_ == N) return false;
    data_[(start_ + count_) % N] = v;
    count_++;
    return true;
  }
  bool empty() const { return count_ == 0; }

 private:
  T data_[N]{};
  std::size_t start_ = 0;
  std::size_t count_ = 0;
};

inline int half(int x) { return x / 2; }
