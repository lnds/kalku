#include "spans.hpp"

#include <algorithm>

namespace kalku {

Spans::Spans(std::vector<std::pair<long, long>> spans) {
  std::sort(spans.begin(), spans.end());
  long furthest = 0;
  for (std::size_t i = 0; i < spans.size(); i++) {
    furthest = i == 0 ? spans[i].second : std::max(furthest, spans[i].second);
    starts_.push_back(spans[i].first);
    reach_.push_back(furthest);
  }
}

bool Spans::reaches(long at, long past) const {
  auto k = std::lower_bound(starts_.begin(), starts_.end(), at) - starts_.begin();
  return k > 0 && reach_[static_cast<std::size_t>(k - 1)] > past;
}

bool Spans::holds(long from, long to) const {
  auto k = std::upper_bound(starts_.begin(), starts_.end(), from) - starts_.begin();
  return k > 0 && reach_[static_cast<std::size_t>(k - 1)] >= to;
}

// A span crosses [from, to) when it starts before `from` and ends inside or
// past it, or starts inside it and ends past `to`; the second is any span
// that starts before `to` and ends past it, as one starting before `from`
// that does is also the first.
bool Spans::crosses(long from, long to) const {
  if (to <= from) return reaches(to, from);
  return reaches(from, from) || reaches(to, to);
}

}  // namespace kalku
