// The loop that answers requests from a reader until `shutdown`.
#pragma once

#include <functional>
#include <string>

#include "framing.hpp"
#include "service.hpp"

namespace kalku {

// Requests are read on a thread of their own, so that an `abort` is heard
// while a cast is still running; everything else is served in order, here.
// That thread also hears the input end. After a `shutdown` it is a driver
// that said all it had to say, and every request before it is answered.
// With none, it is a run that is gone, and `gone` is called.
int serve(LineReader::Source in, const std::function<bool(const std::string&)>& out, Service& s,
          const std::function<void()>& gone);

}  // namespace kalku
