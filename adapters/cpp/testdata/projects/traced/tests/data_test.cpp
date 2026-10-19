#include <fstream>
#include <string>

// The first line of `expected.txt`, in the working directory, says 42.
int main() {
  std::ifstream in("expected.txt");
  std::string first;
  std::getline(in, first);
  return first == "42" ? 0 : 1;
}
