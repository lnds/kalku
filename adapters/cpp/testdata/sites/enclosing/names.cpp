#include <string>

namespace shop {
namespace {
int secret(int x) { return x > 1 ? 1 : 0; }
}  // namespace

class Cart {
 public:
  explicit Cart(int limit) : limit_(limit > 0 ? limit : 1) {}
  ~Cart() { if (limit_ < 0) limit_ = 0; }
  bool full(int n) const;
  bool full(const std::string& name, int n = 1) const { return name.size() > static_cast<std::size_t>(n); }
  bool operator==(const Cart& other) const { return limit_ == other.limit_; }

 private:
  int limit_ = 3;
};

bool Cart::full(int n) const { return n >= limit_; }

int threshold = 7;

template <typename T>
bool bigger(const T& a, const T& b) {
  auto cmp = [](const T& x, const T& y) { return x > y; };
  return cmp(a, b) || secret(1) > 0;
}

}  // namespace shop
