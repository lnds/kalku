#pragma once

namespace range {

int clamp(int v, int lo, int hi);
int sign(int x);
int first_or(const int* p, int fallback);

}  // namespace range
