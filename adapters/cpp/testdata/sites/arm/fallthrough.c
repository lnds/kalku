/* Each case but the default and the last is a site: its body is removed and
   control falls to the next one. */
const char *grade(int score) {
  switch (score / 10) {
    case 10:
    case 9:
      return "A";
    case 8:
      return "B";
    case 7:
      return "C";
    default:
      return "F";
  }
}

int weight(char kind) {
  int w = 0;
  switch (kind) {
    case 's':
      w = 1;
      break;
    case 'm':
      w = 2;
      break;
    case 'l':
      w = 3;
      break;
  }
  return w;
}
