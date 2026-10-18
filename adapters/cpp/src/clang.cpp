#include "clang.hpp"

#include <dirent.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>

#include <cstdlib>
#include <vector>

namespace kalku::cx {

namespace {

#if defined(__APPLE__)
const char* const LIB = "libclang.dylib";
#else
const char* const LIB = "libclang.so";
#endif

std::string from_shell(const std::string& command) {
  std::string out;
  FILE* p = popen((command + " 2>/dev/null").c_str(), "r");
  if (!p) return out;
  char buf[4096];
  while (fgets(buf, sizeof buf, p)) out += buf;
  pclose(p);
  while (!out.empty() && (out.back() == '\n' || out.back() == '\r')) out.pop_back();
  return out;
}

std::string parent(const std::string& path) {
  auto slash = path.find_last_of('/');
  return slash == std::string::npos ? "." : path.substr(0, slash);
}

std::string resolved(const std::string& path) {
  char buf[PATH_MAX];
  return realpath(path.c_str(), buf) ? std::string(buf) : path;
}

// Where a libclang may be, most likely first: the one asked for, the one
// beside the clang on the PATH, Xcode's, then the usual installations.
std::vector<std::string> candidates() {
  std::vector<std::string> out;
  if (const char* asked = std::getenv("KALKU_LIBCLANG")) out.emplace_back(asked);
  std::string clang = from_shell("command -v clang");
  if (!clang.empty()) out.push_back(parent(parent(resolved(clang))) + "/lib/" + LIB);
  std::string libdir = from_shell("llvm-config --libdir");
  if (!libdir.empty()) out.push_back(libdir + "/" + LIB);
#if defined(__APPLE__)
  std::string xcode = from_shell("xcrun --find clang");
  if (!xcode.empty()) out.push_back(parent(parent(xcode)) + "/lib/" + LIB);
  out.emplace_back("/Library/Developer/CommandLineTools/usr/lib/libclang.dylib");
  out.emplace_back("/opt/homebrew/opt/llvm/lib/libclang.dylib");
  out.emplace_back("/usr/local/opt/llvm/lib/libclang.dylib");
#else
  for (int v = 22; v >= 14; v--) {
    out.push_back("/usr/lib/llvm-" + std::to_string(v) + "/lib/libclang.so");
    out.push_back("/usr/lib/llvm-" + std::to_string(v) + "/lib/libclang.so.1");
  }
  out.emplace_back("libclang.so.1");
#endif
  out.emplace_back(LIB);
  return out;
}

Api api;
std::string where;
bool tried = false;
std::string failure;

template <typename F>
bool bind(void* lib, F& slot, const char* name, std::string& missing) {
  slot = reinterpret_cast<F>(dlsym(lib, name));
  if (!slot) {
    missing = name;
    return false;
  }
  return true;
}

bool bind_all(void* lib, std::string& missing) {
  return bind(lib, api.createIndex, "clang_createIndex", missing) &&
         bind(lib, api.disposeIndex, "clang_disposeIndex", missing) &&
         bind(lib, api.parseTranslationUnit2FullArgv, "clang_parseTranslationUnit2FullArgv", missing) &&
         bind(lib, api.disposeTranslationUnit, "clang_disposeTranslationUnit", missing) &&
         bind(lib, api.getNumDiagnostics, "clang_getNumDiagnostics", missing) &&
         bind(lib, api.getDiagnostic, "clang_getDiagnostic", missing) &&
         bind(lib, api.getDiagnosticSeverity, "clang_getDiagnosticSeverity", missing) &&
         bind(lib, api.formatDiagnostic, "clang_formatDiagnostic", missing) &&
         bind(lib, api.disposeDiagnostic, "clang_disposeDiagnostic", missing) &&
         bind(lib, api.getTranslationUnitCursor, "clang_getTranslationUnitCursor", missing) &&
         bind(lib, api.visitChildren, "clang_visitChildren", missing) &&
         bind(lib, api.getCursorKind, "clang_getCursorKind", missing) &&
         bind(lib, api.getCursorExtent, "clang_getCursorExtent", missing) &&
         bind(lib, api.getCursorLocation, "clang_getCursorLocation", missing) &&
         bind(lib, api.getRangeStart, "clang_getRangeStart", missing) &&
         bind(lib, api.getRangeEnd, "clang_getRangeEnd", missing) &&
         bind(lib, api.getSpellingLocation, "clang_getSpellingLocation", missing) &&
         bind(lib, api.getExpansionLocation, "clang_getExpansionLocation", missing) &&
         bind(lib, api.Location_isFromMainFile, "clang_Location_isFromMainFile", missing) &&
         bind(lib, api.getCursorSpelling, "clang_getCursorSpelling", missing) &&
         bind(lib, api.getCString, "clang_getCString", missing) &&
         bind(lib, api.disposeString, "clang_disposeString", missing) &&
         bind(lib, api.getCursorSemanticParent, "clang_getCursorSemanticParent", missing) &&
         bind(lib, api.getCursorReferenced, "clang_getCursorReferenced", missing) &&
         bind(lib, api.Cursor_isNull, "clang_Cursor_isNull", missing) &&
         bind(lib, api.getFile, "clang_getFile", missing) &&
         bind(lib, api.getLocationForOffset, "clang_getLocationForOffset", missing) &&
         bind(lib, api.getRange, "clang_getRange", missing) && bind(lib, api.tokenize, "clang_tokenize", missing) &&
         bind(lib, api.getTokenSpelling, "clang_getTokenSpelling", missing) &&
         bind(lib, api.getTokenExtent, "clang_getTokenExtent", missing) &&
         bind(lib, api.getTokenKind, "clang_getTokenKind", missing) &&
         bind(lib, api.disposeTokens, "clang_disposeTokens", missing) &&
         bind(lib, api.getClangVersion, "clang_getClangVersion", missing) &&
         bind(lib, api.CompilationDatabase_fromDirectory, "clang_CompilationDatabase_fromDirectory", missing) &&
         bind(lib, api.CompilationDatabase_dispose, "clang_CompilationDatabase_dispose", missing) &&
         bind(lib, api.CompilationDatabase_getCompileCommands, "clang_CompilationDatabase_getCompileCommands",
              missing) &&
         bind(lib, api.CompilationDatabase_getAllCompileCommands, "clang_CompilationDatabase_getAllCompileCommands",
              missing) &&
         bind(lib, api.CompileCommands_dispose, "clang_CompileCommands_dispose", missing) &&
         bind(lib, api.CompileCommands_getSize, "clang_CompileCommands_getSize", missing) &&
         bind(lib, api.CompileCommands_getCommand, "clang_CompileCommands_getCommand", missing) &&
         bind(lib, api.CompileCommand_getDirectory, "clang_CompileCommand_getDirectory", missing) &&
         bind(lib, api.CompileCommand_getFilename, "clang_CompileCommand_getFilename", missing) &&
         bind(lib, api.CompileCommand_getNumArgs, "clang_CompileCommand_getNumArgs", missing) &&
         bind(lib, api.CompileCommand_getArg, "clang_CompileCommand_getArg", missing);
}

}  // namespace

const Api* load(std::string* why) {
  if (tried) {
    if (why) *why = failure;
    return failure.empty() ? &api : nullptr;
  }
  tried = true;
  std::vector<std::string> looked;
  for (const auto& path : candidates()) {
    void* lib = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
      looked.push_back(path);
      continue;
    }
    std::string missing;
    if (!bind_all(lib, missing)) {
      looked.push_back(path + " (it has no " + missing + ")");
      dlclose(lib);
      continue;
    }
    where = path;
    return &api;
  }
  failure = "no libclang to read C++ with; looked for ";
  for (std::size_t i = 0; i < looked.size(); i++) failure += (i ? ", " : "") + looked[i];
  failure += ". Install Clang, or name one with KALKU_LIBCLANG";
  if (why) *why = failure;
  return nullptr;
}

const std::string& loaded_from() { return where; }

const std::string& resource_dir() {
  static const std::string dir = [] {
    if (where.empty()) return std::string();
    std::string clang = parent(resolved(where)) + "/clang";
    std::string best;
    if (DIR* d = opendir(clang.c_str())) {
      while (dirent* e = readdir(d)) {
        std::string name = e->d_name;
        if (name == "." || name == "..") continue;
        std::string candidate = clang + "/" + name;
        struct stat st;
        if (stat((candidate + "/include/stddef.h").c_str(), &st) == 0 && candidate > best) best = candidate;
      }
      closedir(d);
    }
    return best;
  }();
  return dir;
}

std::string text(const Api& a, String s) {
  const char* c = a.getCString(s);
  std::string out = c ? c : "";
  a.disposeString(s);
  return out;
}

}  // namespace kalku::cx
