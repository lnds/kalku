int score(char c) {
  switch (c) {
    case 'a':
    case 'b':
      return 1;
    case 'c': {
      int bonus = 2;
      return bonus;
    }
    default:
      return 0;
    case 'z':
      return 26;
  }
}

int nested(int x, int y) {
  switch (x) {
    case 1:
      switch (y) {
        case 1:
          return 11;
        case 2:
          return 12;
      }
      return 10;
    case 2:
      return 20;
  }
  return 0;
}
