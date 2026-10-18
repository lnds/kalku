// SHA-256, for the ids of sites: the same text gives the same id on every
// machine.
#pragma once

#include <string>

namespace kalku {

// The digest of `data`, in lowercase hexadecimal.
std::string sha256_hex(const std::string& data);

}  // namespace kalku
