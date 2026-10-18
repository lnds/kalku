struct Gate {
  bool open = false;
  bool armed = false;

  bool passes(bool key) const { return open || (armed && key); }
  // The spelled-out operators are left alone.
  bool spelled(bool key) const { return open and key; }
};

bool either(bool a, bool b) { return a || b; }
