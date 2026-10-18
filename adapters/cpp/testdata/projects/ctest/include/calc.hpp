#pragma once

namespace calc {

int clamp(int v, int lo, int hi);
int first_or(const int* p, int fallback);
int sign(int x);

// Defined here, so a wekufe in it rebuilds every file that includes it.
inline bool positive(int x) { return x > 0; }

}  // namespace calc
