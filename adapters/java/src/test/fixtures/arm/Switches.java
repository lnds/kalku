package fx.arm;

class Switches {
  // A default remains, so each case can go.
  String name(int n) {
    switch (n) {
      case 1:
        return "one";
      case 2:
        return "two";
      default:
        return "many";
    }
  }

  // An empty case falls through to the next: deleting it sends its value to the default.
  int group(char c) {
    switch (c) {
      case 'a':
      case 'e':
        return 1;
      default:
        return 0;
    }
  }

  // No default: nothing is proposed.
  int bare(int n) {
    int out = 0;
    switch (n) {
      case 1:
        out = 10;
        break;
      case 2:
        out = 20;
        break;
    }
    return out;
  }

  // A lone default has no case to delete.
  int lone(int n) {
    switch (n) {
      default:
        return n;
    }
  }

  // A switch inside a case: each has its own cases.
  int nested(int a, int b) {
    switch (a) {
      case 1:
        switch (b) {
          case 7: return 70;
          default: return 0;
        }
      default:
        return -1;
    }
  }
}
