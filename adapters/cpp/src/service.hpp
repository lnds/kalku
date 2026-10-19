// Answers requests, one at a time, in the order they came.
#pragma once

#include <atomic>
#include <map>
#include <memory>
#include <string>

#include "clang.hpp"
#include "project.hpp"
#include "protocol.hpp"

namespace kalku {

struct Reply {
  std::string line;
  // A cast that was aborted gets no answer of its own.
  bool quiet = false;
  bool last = false;
};

Reply refuse(const DecodeError& e);

class Service {
 public:
  Service();
  // `cancel` becomes true when the kaikai side aborts the request, a cast.
  Reply handle(const Request& r, const std::atomic<bool>& cancel);

 private:
  Reply greet(const Request& r);
  std::string prepare(std::int64_t id);
  std::string sites(std::int64_t id, const SitesRequest& r);
  std::string baseline(std::int64_t id, const std::map<std::string, std::string>& reuse);
  Reply cast(std::int64_t id, const CastRequest& c, const std::atomic<bool>& cancel);
  std::string reload(std::int64_t id, const std::vector<std::string>& files);
  bool ready_to_read(std::string* why);

  const cx::Api* clang_ = nullptr;
  std::unique_ptr<Project> project_;
  bool copied_ = false;
  bool prepared_ = false;
};

}  // namespace kalku
