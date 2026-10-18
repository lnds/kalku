#include "serve.hpp"

#include <atomic>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

namespace kalku {

namespace {

struct Heard {
  bool too_long = false;
  bool decoded = false;
  Request req;
  DecodeError err;
};

}  // namespace

int serve(LineReader::Source in, const std::function<bool(const std::string&)>& out, Service& s,
          const std::function<void()>& gone) {
  std::mutex mu;
  std::condition_variable cv;
  std::deque<Heard> queue;
  bool closed = false;
  std::int64_t running = -1;
  std::atomic<bool> cancel{false};

  std::thread reader([&] {
    LineReader lines(std::move(in), MAX_LINE);
    std::string line;
    for (;;) {
      LineReader::Got got = lines.next(line);
      if (got == LineReader::Got::End) {
        gone();
        std::lock_guard<std::mutex> g(mu);
        closed = true;
        cv.notify_all();
        return;
      }
      Heard h;
      if (got == LineReader::Got::TooLong) {
        h.too_long = true;
      } else if (auto r = decode(line, &h.err)) {
        h.decoded = true;
        h.req = std::move(*r);
      }
      bool last = h.decoded && h.req.type == "shutdown";
      {
        std::lock_guard<std::mutex> g(mu);
        if (h.decoded && h.req.type == "abort" && h.req.abort == running) cancel.store(true);
        queue.push_back(std::move(h));
        if (last) closed = true;
        cv.notify_all();
      }
      if (last) return;
    }
  });
  reader.detach();

  for (;;) {
    Heard h;
    {
      std::unique_lock<std::mutex> lock(mu);
      cv.wait(lock, [&] { return !queue.empty() || closed; });
      if (queue.empty()) return 0;
      h = std::move(queue.front());
      queue.pop_front();
      if (h.decoded) {
        running = h.req.id;
        cancel.store(false);
      }
    }
    Reply reply;
    if (h.too_long) {
      reply.line =
          error(0, "bad_request", "line_too_long: a line longer than " + std::to_string(MAX_LINE) + " bytes", false);
    } else if (!h.decoded) {
      reply = refuse(h.err);
    } else {
      reply = s.handle(h.req, cancel);
    }
    {
      std::lock_guard<std::mutex> g(mu);
      running = -1;
    }
    if (reply.quiet) continue;
    if (!out(reply.line + "\n")) return 1;
    if (reply.last) return 0;
  }
}

}  // namespace kalku
