// A `return` wekufe gives back a neutral value instead of what the function
// computed. The value is read from the return type the function declares,
// where it names one plainly, so the wekufe stays a program the project's
// own `tsc` accepts: `0` for a `number` (`1` for a `0`), `0n` for a
// `bigint`, `""` for a `string`, `false` for a `boolean`, `[]` for an array,
// and in an `async` function the same for what its `Promise<T>` holds.
// Where the type it declares holds `undefined` (`any`, `unknown`, a union
// with `undefined`) it is `undefined`, and so in a JavaScript file, where
// nothing is declared. Any other declared type gets no site: the signature
// does not say how to write its neutral value, and under a runner that
// checks types, as ts-jest does, `undefined` would be a compile error. Nor
// does a TypeScript function that declares no type: `tsc` infers it from the
// value, and `undefined` changes what the function's callers are given.
//
// The value of a `return <expr>`, and the expression an arrow function's
// body is, are sites. Left out:
//
//   - an arrow whose body is an assignment or a `void`, a statement written
//     as an expression: what it would lose is the effect, not a value;
//   - a value that already is the neutral one, or is `null`, `undefined` or
//     `void 0`, which a caller tells apart from `undefined` rarely;
//   - `true` and `false`, which the `literal` spell already flips;
//   - a function that declares `void`, `never` or `undefined`, a
//     constructor, a `set` accessor, and a generator, whose returned value
//     nobody reads, or reads only through `yield*`.

import ts from "typescript";

export interface ReturnSite {
  from: number;
  to: number;
  replacement: string;
}

/** The `return` site of a node, when it is a returned value worth a wekufe. */
export function returnSite(node: ts.Node, sf: ts.SourceFile): ReturnSite | undefined {
  let value: ts.Expression;
  let fn: ts.SignatureDeclaration | undefined;
  if (ts.isReturnStatement(node) && node.expression !== undefined) {
    value = node.expression;
    fn = owner(node);
  } else if (ts.isArrowFunction(node) && !ts.isBlock(node.body)) {
    if (statementLike(node.body)) return undefined;
    value = node.body;
    fn = node;
  } else {
    return undefined;
  }
  if (fn === undefined || unread(fn)) return undefined;
  const replacement = neutral(fn.type, isAsync(fn), value, javascript(sf));
  if (replacement === undefined) return undefined;
  return { from: value.getStart(sf), to: value.getEnd(), replacement };
}

// The function a `return` returns from: the nearest one around it.
function owner(node: ts.Node): ts.SignatureDeclaration | undefined {
  for (let n = node.parent; n !== undefined; n = n.parent) {
    if (ts.isFunctionLike(n)) return n;
    if (ts.isClassStaticBlockDeclaration(n) || ts.isSourceFile(n)) return undefined;
  }
  return undefined;
}

function unread(fn: ts.SignatureDeclaration): boolean {
  if (ts.isConstructorDeclaration(fn) || ts.isSetAccessor(fn)) return true;
  return (
    (ts.isFunctionDeclaration(fn) || ts.isFunctionExpression(fn) || ts.isMethodDeclaration(fn)) &&
    fn.asteriskToken !== undefined
  );
}

function isAsync(fn: ts.SignatureDeclaration): boolean {
  if (!ts.canHaveModifiers(fn)) return false;
  return (ts.getModifiers(fn) ?? []).some((m) => m.kind === ts.SyntaxKind.AsyncKeyword);
}

function statementLike(body: ts.ConciseBody): boolean {
  const e = skipParens(body as ts.Expression);
  if (ts.isVoidExpression(e)) return true;
  const op = ts.isBinaryExpression(e) ? e.operatorToken.kind : undefined;
  return op !== undefined && op >= ts.SyntaxKind.FirstAssignment && op <= ts.SyntaxKind.LastAssignment;
}

// The replacement for `value` given the declared type, or undefined where no
// wekufe is proposed.
function neutral(
  type: ts.TypeNode | undefined,
  async: boolean,
  value: ts.Expression,
  javascript: boolean,
): string | undefined {
  const v = skipParens(value);
  if (v.kind === ts.SyntaxKind.TrueKeyword || v.kind === ts.SyntaxKind.FalseKeyword) return undefined;
  if (nothing(v)) return undefined;
  if (type === undefined) return javascript ? "undefined" : undefined;
  const declared = async ? promised(type) : type;
  if (declared === undefined) return undefined;
  switch (declared.kind) {
    case ts.SyntaxKind.NumberKeyword:
      return ts.isNumericLiteral(v) && Number(v.text) === 0 ? "1" : "0";
    case ts.SyntaxKind.BigIntKeyword:
      return ts.isBigIntLiteral(v) && /^0+n$/.test(v.text) ? "1n" : "0n";
    case ts.SyntaxKind.StringKeyword:
      return emptyString(v) ? undefined : '""';
    case ts.SyntaxKind.BooleanKeyword:
      return "false";
  }
  if (arrayType(declared)) return ts.isArrayLiteralExpression(v) && v.elements.length === 0 ? undefined : "[]";
  return admitsUndefined(declared) ? "undefined" : undefined;
}

// What an `async` function's `Promise<T>` holds; undefined when it declares
// something else, whose neutral the signature does not say how to write.
function promised(type: ts.TypeNode): ts.TypeNode | undefined {
  if (!ts.isTypeReferenceNode(type)) return undefined;
  if (!ts.isIdentifier(type.typeName) || type.typeName.text !== "Promise") return undefined;
  return type.typeArguments?.length === 1 ? type.typeArguments[0] : undefined;
}

// A declared type `undefined` is a value of without a cast: `any`, `unknown`,
// or a union holding `undefined`. `void`, `never` and `undefined` alone are
// the types of functions whose value nobody reads.
function admitsUndefined(t: ts.TypeNode): boolean {
  if (ts.isParenthesizedTypeNode(t)) return admitsUndefined(t.type);
  if (t.kind === ts.SyntaxKind.AnyKeyword || t.kind === ts.SyntaxKind.UnknownKeyword) return true;
  if (!ts.isUnionTypeNode(t)) return false;
  return t.types.some((m) => m.kind === ts.SyntaxKind.UndefinedKeyword || admitsUndefined(m));
}

function arrayType(t: ts.TypeNode): boolean {
  if (ts.isArrayTypeNode(t)) return true;
  if (ts.isTypeOperatorNode(t) && t.operator === ts.SyntaxKind.ReadonlyKeyword) return arrayType(t.type);
  return (
    ts.isTypeReferenceNode(t) &&
    ts.isIdentifier(t.typeName) &&
    (t.typeName.text === "Array" || t.typeName.text === "ReadonlyArray")
  );
}

function javascript(sf: ts.SourceFile): boolean {
  return /\.[cm]?jsx?$/.test(sf.fileName);
}

function nothing(e: ts.Expression): boolean {
  if (e.kind === ts.SyntaxKind.NullKeyword) return true;
  if (ts.isIdentifier(e) && e.text === "undefined") return true;
  return ts.isVoidExpression(e) && ts.isNumericLiteral(skipParens(e.expression));
}

function emptyString(e: ts.Expression): boolean {
  return (ts.isStringLiteral(e) || ts.isNoSubstitutionTemplateLiteral(e)) && e.text === "";
}

function skipParens(e: ts.Expression): ts.Expression {
  while (ts.isParenthesizedExpression(e)) e = e.expression;
  return e;
}
