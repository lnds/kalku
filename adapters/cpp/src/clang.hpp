// libclang, loaded at run time.
//
// The kalku links nothing of LLVM: it opens the libclang the machine has
// (the one beside the project's own clang, Xcode's, a distribution's) and
// calls its C interface, which is stable across releases. So one binary
// works with whatever Clang is installed, and a release need not be built
// against any. Only the declarations used here are written down.
#pragma once

#include <string>

namespace kalku::cx {

using Index = void*;
using TranslationUnit = void*;
using File = void*;
using Diagnostic = void*;
using CompilationDatabase = void*;
using CompileCommands = void*;
using CompileCommand = void*;
using ClientData = void*;

struct String {
  const void* data;
  unsigned private_flags;
};

struct Cursor {
  int kind;
  int xdata;
  const void* data[3];
};

struct SourceLocation {
  const void* ptr_data[2];
  unsigned int_data;
};

struct SourceRange {
  const void* ptr_data[2];
  unsigned begin_int_data;
  unsigned end_int_data;
};

struct Token {
  unsigned int_data[4];
  void* ptr_data;
};

enum VisitResult { Break = 0, Continue = 1, Recurse = 2 };
using Visitor = VisitResult (*)(Cursor, Cursor, ClientData);

// The kinds of cursor this kalku tells apart, by their values in libclang.
enum Kind {
  StructDecl = 2,
  UnionDecl = 3,
  ClassDecl = 4,
  EnumDecl = 5,
  FieldDecl = 6,
  EnumConstantDecl = 7,
  FunctionDecl = 8,
  VarDecl = 9,
  ParmDecl = 10,
  CXXMethod = 21,
  Namespace = 22,
  LinkageSpec = 23,
  Constructor = 24,
  Destructor = 25,
  ConversionFunction = 26,
  NonTypeTemplateParameter = 28,
  FunctionTemplate = 30,
  ClassTemplate = 31,
  ClassTemplatePartialSpecialization = 32,
  FirstRef = 40,
  LastRef = 51,
  FirstExpr = 100,
  DeclRefExpr = 101,
  MemberRefExpr = 102,
  CallExpr = 103,
  IntegerLiteral = 106,
  StringLiteral = 109,
  ParenExpr = 111,
  UnaryOperator = 112,
  BinaryOperator = 114,
  ConditionalOperator = 116,
  CXXBoolLiteralExpr = 130,
  UnaryExpr = 136,
  LambdaExpr = 144,
  ConceptSpecializationExpr = 153,
  RequiresExpr = 154,
  LastExpr = 199,
  FirstStmt = 200,
  CompoundStmt = 202,
  CaseStmt = 203,
  DefaultStmt = 204,
  IfStmt = 205,
  SwitchStmt = 206,
  WhileStmt = 207,
  DoStmt = 208,
  ForStmt = 209,
  CXXForRangeStmt = 225,
  DeclStmt = 231,
  LastStmt = 299,
  TranslationUnit_ = 350,
  FirstAttr = 400,
  LastAttr = 499,
  MacroExpansion = 502,
  StaticAssert = 602,
  ConceptDecl = 604,
};

enum TokenKind { Punctuation = 0, Keyword = 1, Identifier = 2, Literal = 3, Comment = 4 };

struct UnsavedFile {
  const char* filename;
  const char* contents;
  unsigned long length;
};

// The functions of libclang this kalku calls.
struct Api {
  Index (*createIndex)(int, int);
  void (*disposeIndex)(Index);
  int (*parseTranslationUnit2FullArgv)(Index, const char*, const char* const*, int, UnsavedFile*, unsigned, unsigned,
                                       TranslationUnit*);
  void (*disposeTranslationUnit)(TranslationUnit);
  unsigned (*getNumDiagnostics)(TranslationUnit);
  Diagnostic (*getDiagnostic)(TranslationUnit, unsigned);
  int (*getDiagnosticSeverity)(Diagnostic);
  String (*formatDiagnostic)(Diagnostic, unsigned);
  SourceLocation (*getDiagnosticLocation)(Diagnostic);
  unsigned (*getDiagnosticNumRanges)(Diagnostic);
  SourceRange (*getDiagnosticRange)(Diagnostic, unsigned);
  void (*disposeDiagnostic)(Diagnostic);
  Cursor (*getTranslationUnitCursor)(TranslationUnit);
  unsigned (*visitChildren)(Cursor, Visitor, ClientData);
  int (*getCursorKind)(Cursor);
  SourceRange (*getCursorExtent)(Cursor);
  SourceLocation (*getCursorLocation)(Cursor);
  SourceLocation (*getRangeStart)(SourceRange);
  SourceLocation (*getRangeEnd)(SourceRange);
  void (*getSpellingLocation)(SourceLocation, File*, unsigned*, unsigned*, unsigned*);
  void (*getExpansionLocation)(SourceLocation, File*, unsigned*, unsigned*, unsigned*);
  int (*Location_isFromMainFile)(SourceLocation);
  String (*getCursorSpelling)(Cursor);
  const char* (*getCString)(String);
  void (*disposeString)(String);
  Cursor (*getCursorSemanticParent)(Cursor);
  Cursor (*getCursorReferenced)(Cursor);
  int (*Cursor_isNull)(Cursor);
  File (*getFile)(TranslationUnit, const char*);
  SourceLocation (*getLocationForOffset)(TranslationUnit, File, unsigned);
  SourceRange (*getRange)(SourceLocation, SourceLocation);
  void (*tokenize)(TranslationUnit, SourceRange, Token**, unsigned*);
  String (*getTokenSpelling)(TranslationUnit, Token);
  SourceRange (*getTokenExtent)(TranslationUnit, Token);
  int (*getTokenKind)(Token);
  void (*disposeTokens)(TranslationUnit, Token*, unsigned);
  String (*getClangVersion)();
  CompilationDatabase (*CompilationDatabase_fromDirectory)(const char*, int*);
  void (*CompilationDatabase_dispose)(CompilationDatabase);
  CompileCommands (*CompilationDatabase_getCompileCommands)(CompilationDatabase, const char*);
  CompileCommands (*CompilationDatabase_getAllCompileCommands)(CompilationDatabase);
  void (*CompileCommands_dispose)(CompileCommands);
  unsigned (*CompileCommands_getSize)(CompileCommands);
  CompileCommand (*CompileCommands_getCommand)(CompileCommands, unsigned);
  String (*CompileCommand_getDirectory)(CompileCommand);
  String (*CompileCommand_getFilename)(CompileCommand);
  unsigned (*CompileCommand_getNumArgs)(CompileCommand);
  String (*CompileCommand_getArg)(CompileCommand, unsigned);
};

// libclang, opened once; nullptr when none could be, with why in `why`.
const Api* load(std::string* why);

// Where libclang was found, once `load` succeeded.
const std::string& loaded_from();

// The headers that come with this libclang (`stddef.h`, `stdarg.h`), which
// it finds only when told where they are; empty when there are none.
const std::string& resource_dir();

// A libclang string as a std::string, disposed of.
std::string text(const Api& api, String s);

}  // namespace kalku::cx
