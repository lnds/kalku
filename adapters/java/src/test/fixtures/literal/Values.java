package fx.literal;

class Values {
  static final int LIMIT = 5;
  static final long BIG = 10L;
  static final String NAME = "kalku";

  int numbers() {
    int zero = 0;
    int negative = -1;
    long lower = 7l;
    return zero + negative + (int) lower + 41;
  }

  // The successor would not fit: nothing is proposed.
  long edges() {
    int top = 2147483647;
    int bottom = -2147483648;
    long far = 9223372036854775807L;
    return top + bottom + far;
  }

  // Other bases and underscores are left alone, and so are reals and characters.
  double shapes() {
    int hex = 0x1F;
    int octal = 017;
    int binary = 0b101;
    int grouped = 1_000;
    char c = 'c';
    float f = 1.5f;
    return hex + octal + binary + grouped + c + f + 2.5;
  }

  boolean flags() {
    boolean on = true;
    boolean off = false;
    return on || off;
  }

  String texts() {
    String empty = "";
    String quoted = "say \"hi\"";
    return empty + quoted + "tail";
  }

  // An annotation's values are metadata.
  @SuppressWarnings("unchecked")
  @Deprecated
  int annotated() {
    return 3;
  }

  // A case label is structure; what the case does is code.
  int labels(int n, String s) {
    switch (s) {
      case "a":
        return 1;
      default:
        break;
    }
    switch (n) {
      case 1:
        return 100;
      default:
        return 200;
    }
  }

  @interface Marker {
    int level() default 4;

    String note() default "none";
  }
}
