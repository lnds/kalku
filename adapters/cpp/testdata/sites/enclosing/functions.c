/* A site's enclosing is the function it is in, or the variable a file-scope
   initializer gives its value to. */
static int threshold = 3 > 2;

static int helper(int x) { return x > threshold; }

int outer(int x) {
  if (x < 0) return helper(-x);
  return helper(x) == 1;
}
