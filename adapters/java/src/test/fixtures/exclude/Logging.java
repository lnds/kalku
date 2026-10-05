package fx.exclude;

class Logging {
  private final Log log = new Log();

  // The arguments of an excluded call get no sites, and the call itself is not dropped.
  int work(int n) {
    log.info("working on " + n + (n > 3 ? " big" : " small"));
    System.out.println("n = " + n);
    audit("kept", n > 0);
    return n + 1;
  }

  // Only the names the patterns state are excluded.
  void audit(String what, boolean ok) {
    log().info("not a plain name: " + what);
    logger.info("another name");
  }

  private Log log() {
    return log;
  }

  private final Log logger = new Log();

  static class Log {
    void info(String s) {}
  }
}
