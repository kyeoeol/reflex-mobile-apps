// `./reflex check`: enforces the Reflex discipline mechanically. Humans find it hard; agents repeat patterns,
// so the patterns are checked, not hoped for. Exit code 1 on any violation. No dependencies.

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";

const ROOT = resolve(import.meta.dir, "..");
const SWIFT_CORE = join(ROOT, "ios/Packages/AppCore/Sources/AppCore");
const SWIFT_KIT = join(ROOT, "ios/Packages/IntrospectionKit/Sources/IntrospectionKit");
const SWIFT_MOBILE = join(ROOT, "ios/AppMobile/AppMobile");
const KOTLIN_CORE = join(ROOT, "android/core/src/main/kotlin");
const KOTLIN_APP = join(ROOT, "android/app/src");
const FIXTURES = join(ROOT, "fixtures");
const BEHAVIOR = join(ROOT, "tests/behavior");
const LIVE = join(ROOT, "tests/reflex-live");

const problems: string[] = [];
const report = (file: string, line: number | null, message: string) =>
  problems.push(`${relative(ROOT, file)}${line ? `:${line}` : ""}  ${message}`);

function files(dir: string, test: (path: string) => boolean): string[] {
  if (!existsSync(dir)) return [];
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    if (entry === ".build" || entry === "build" || entry === "node_modules") continue;
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...files(path, test));
    else if (test(path)) out.push(path);
  }
  return out.sort();
}

/** Blanks out comments and string contents (keeping line structure) so rules only see code. */
function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/"""[\s\S]*?"""/g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/"(?:\\.|[^"\\\n])*"/g, (m) => `"${" ".repeat(Math.max(0, m.length - 2))}"`)
    .replace(/\/\/[^\n]*/g, (m) => " ".repeat(m.length));
}

const lineOf = (source: string, index: number) => source.slice(0, index).split("\n").length;

function forbid(file: string, source: string, pattern: RegExp, message: string) {
  for (const match of source.matchAll(new RegExp(pattern.source, pattern.flags.includes("g") ? pattern.flags : pattern.flags + "g"))) {
    report(file, lineOf(source, match.index!), message);
  }
}

const pascal = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

// 1. Core purity ------------------------------------------------------------------------------------
for (const file of [...files(SWIFT_CORE, (p) => p.endsWith(".swift")), ...files(SWIFT_KIT, (p) => p.endsWith(".swift"))]) {
  const code = codeOnly(readFileSync(file, "utf8"));
  forbid(file, code, /^\s*import\s+(UIKit|SwiftUI|AppKit|WatchKit|Combine|Security|LocalAuthentication|ReflexHost)\b/m,
    "Core purity: no UI/platform/host imports in Core or IntrospectionKit. Declare a protocol in Foundation/Platform.swift instead.");
  forbid(file, code, /#if\s+os\(/, "Core purity: no platform conditionals in Core.");
}
for (const file of files(KOTLIN_CORE, (p) => p.endsWith(".kt"))) {
  const code = codeOnly(readFileSync(file, "utf8"));
  forbid(file, code, /^\s*import\s+(android|androidx)\./m,
    "Core purity: no android.*/androidx.* in core. Declare an interface in foundation/Platform.kt instead.");
  forbid(file, code, /^\s*import\s+app\.reflex\.host\./m, "Core never depends on reflex-host.");
}

// 2. Nodes are unconditional ---------------------------------------------------------------------------
const swiftNodes = files(join(SWIFT_CORE, "Features"), (p) => p.endsWith("+Introspection.swift"));
const kotlinNodes = files(KOTLIN_CORE, (p) => p.includes("/features/") && p.endsWith("IntrospectionNode.kt"));
for (const file of swiftNodes) {
  const code = codeOnly(readFileSync(file, "utf8"));
  forbid(file, code, /\b(if|guard|switch|while|for)\b|\?\?|\s\?\s[^:]+\s:\s/,
    "Node rule 2: no conditionals in a node. Put the rule in the ViewModel's update and the result in state.");
}
for (const file of kotlinNodes) {
  const code = codeOnly(readFileSync(file, "utf8"));
  forbid(file, code, /\b(if|when|while|for)\b|\?:|\?\.let|takeIf|takeUnless/,
    "Node rule 2: no conditionals in a node. Put the rule in the ViewModel's update and the result in state.");
}

// 3. Single state writer -----------------------------------------------------------------------------------
function braceBody(code: string, start: number): [number, number] {
  const open = code.indexOf("{", start);
  let depth = 0;
  for (let i = open; i < code.length; i++) {
    if (code[i] === "{") depth++;
    else if (code[i] === "}" && --depth === 0) return [open, i];
  }
  return [open, code.length];
}
for (const file of files(join(SWIFT_CORE, "Features"), (p) => p.endsWith("ViewModel.swift"))) {
  const code = codeOnly(readFileSync(file, "utf8"));
  const updateAt = code.search(/func\s+update\s*\(\s*state:/);
  if (updateAt < 0) {
    report(file, null, "ViewModel contract: missing `func update(state:message:)`.");
    continue;
  }
  const [from, to] = braceBody(code, updateAt);
  for (const m of code.matchAll(/\bstate\.\w+\s*(=(?!=)|\+=|-=|\*=|\/=|\.append\(|\.remove|\.insert\()/g)) {
    if (m.index! < from || m.index! > to) {
      report(file, lineOf(code, m.index!), "Single writer: state changes only inside update(state:message:).");
    }
  }
  // Stored State fields can't be written from views or other Core files (Kotlin gets this from an immutable State).
  const stateAt = code.search(/class\s+State\b/);
  if (stateAt >= 0) {
    const [stateFrom, stateTo] = braceBody(code, stateAt);
    for (const m of code.slice(stateFrom, stateTo).matchAll(/^\s*(?:public\s+|internal\s+)?var\s+\w+[^{\n]*$/gm)) {
      report(file, lineOf(code, stateFrom + m.index!),
        "Single writer: stored State fields are `public fileprivate(set) var` (computed fields may stay `public var`).");
    }
  }
  if (!/enum\s+Message\b[\s\S]*?case\s+viewAction\(Action\)/.test(code)) {
    report(file, null, "ViewModel contract: Message must have `case viewAction(Action)`.");
  }
}
for (const file of files(KOTLIN_CORE, (p) => p.includes("/features/") && p.endsWith("ViewModel.kt"))) {
  const code = codeOnly(readFileSync(file, "utf8"));
  forbid(file, code, /MutableStateFlow|\b_state\b/, "Single writer: features never own mutable state; return new state from update().");
  const stateClass = code.match(/data class State\(([\s\S]*?)\)\s*(\{|\n)/);
  if (stateClass && /\bvar\b/.test(stateClass[1])) report(file, lineOf(code, stateClass.index!), "State must be immutable (val only).");
  if (!/override fun update\(state: State, message: Message\): State/.test(code)) {
    report(file, null, "ViewModel contract: missing `override fun update(state: State, message: Message): State`.");
  }
}

// 4. Node coverage + 5. cross-platform parity of the node surface ---------------------------------------
type Surface = Map<string, { titles: string[]; file: string }>;
const swiftSurface: Surface = new Map();
const kotlinSurface: Surface = new Map();

for (const node of swiftNodes) {
  const vmFile = node.replace("+Introspection.swift", ".swift");
  const nodeCode = readFileSync(node, "utf8");
  const name = nodeCode.match(/let name = "(\w+)"/)?.[1];
  const titles = [...nodeCode.matchAll(/IntrospectionAction\(title:\s*"(\w+)"/g)].map((m) => m[1]);
  if (name) swiftSurface.set(name, { titles, file: node });
  if (!existsSync(vmFile)) continue;
  const vm = codeOnly(readFileSync(vmFile, "utf8"));
  const actionEnum = vm.match(/enum\s+Action\b[^{]*\{([\s\S]*?)\n\s*\}/)?.[1] ?? "";
  const cases = [...actionEnum.matchAll(/case\s+([^\n]+)/g)].flatMap((m) =>
    m[1].split(/,(?![^(]*\))/).map((c) => c.trim().match(/^(\w+)/)?.[1]).filter(Boolean) as string[]);
  for (const c of cases) {
    if (!titles.includes(pascal(c))) report(node, null, `Node coverage: Action.${c} has no IntrospectionAction(title: "${pascal(c)}").`);
  }
  const expected = vmFile.split("/").pop()!.replace("ViewModel.swift", "");
  if (name && name !== expected) report(node, null, `Naming: node name should be "${expected}" (ViewModel name without the suffix).`);
}

for (const node of kotlinNodes) {
  const vmFile = node.replace("ViewModelIntrospectionNode.kt", "ViewModel.kt");
  const nodeCode = readFileSync(node, "utf8");
  const name = nodeCode.match(/override val name = "(\w+)"/)?.[1];
  const titles = [...nodeCode.matchAll(/IntrospectionAction\(\s*"(\w+)"/g)].map((m) => m[1]);
  if (name) kotlinSurface.set(name, { titles, file: node });
  if (!existsSync(vmFile)) continue;
  const vm = codeOnly(readFileSync(vmFile, "utf8"));
  const actionBlock = vm.match(/sealed interface Action\s*\{([\s\S]*?)\n\s{4}\}/)?.[1] ?? "";
  const cases = [...actionBlock.matchAll(/data (?:object|class)\s+(\w+)/g)].map((m) => m[1]);
  for (const c of cases) {
    if (!titles.includes(c)) report(node, null, `Node coverage: Action.${c} has no IntrospectionAction("${c}", ...).`);
  }
  const className = node.split("/").pop()!.replace(".kt", "");
  const registrations = files(KOTLIN_CORE, (p) => p.endsWith("IntrospectionRegistrations.kt")).map((f) => readFileSync(f, "utf8")).join("\n");
  if (!registrations.includes(`${className}(`)) report(node, null, `Registration: ${className} is not registered in IntrospectionRegistrations.kt.`);
}

for (const [name, swift] of swiftSurface) {
  const kotlin = kotlinSurface.get(name);
  if (!kotlin) {
    report(swift.file, null, `Parity: node "${name}" exists on iOS but not on Android.`);
    continue;
  }
  if (swift.titles.join(",") !== kotlin.titles.join(",")) {
    report(kotlin.file, null, `Parity: "${name}" actions differ. iOS [${swift.titles}] vs Android [${kotlin.titles}].`);
  }
}
for (const [name, kotlin] of kotlinSurface) {
  if (!swiftSurface.has(name)) report(kotlin.file, null, `Parity: node "${name}" exists on Android but not on iOS.`);
}

// GraphQL operation names must match across platforms (fixtures match on them).
const swiftOps = new Set(files(SWIFT_CORE, (p) => p.endsWith(".swift")).flatMap((f) =>
  [...readFileSync(f, "utf8").matchAll(/static let operationName = "(\w+)"/g)].map((m) => m[1])));
const kotlinOps = new Set(files(KOTLIN_CORE, (p) => p.endsWith(".kt")).flatMap((f) =>
  [...readFileSync(f, "utf8").matchAll(/GraphQLOperation\(\s*"(\w+)"/g)].map((m) => m[1])));
for (const op of swiftOps) if (!kotlinOps.has(op)) report(SWIFT_CORE, null, `Parity: GraphQL operation "${op}" exists on iOS but not on Android.`);
for (const op of kotlinOps) if (!swiftOps.has(op)) report(KOTLIN_CORE, null, `Parity: GraphQL operation "${op}" exists on Android but not on iOS.`);

// 6. Views only read state and send actions -------------------------------------------------------------
const isDebugOnly = (p: string) => p.includes("/Debug/") || p.includes("/src/debug/");
for (const file of [...files(SWIFT_MOBILE, (p) => p.endsWith(".swift")), ...files(KOTLIN_APP, (p) => p.endsWith(".kt"))]) {
  const code = codeOnly(readFileSync(file, "utf8"));
  forbid(file, code, /\.update\(\s*state|send\(\s*message|sendMessage\(|\.state\.\w+\s*=(?!=)/,
    "View contract: views read state and call send(action:) only.");
  if (!isDebugOnly(file)) {
    forbid(file, code, /^\s*import\s+(IntrospectionKit|ReflexHost)\b|^\s*import\s+app\.reflex\.(introspection|host)\./m,
      "Introspection is never used by the app (only by DEBUG-only code in Debug/ or src/debug/).");
  }
}

// 7. Behavior tests: transcripts, not scripts -----------------------------------------------------------
const testFiles = [...files(BEHAVIOR, (p) => p.endsWith(".ts")), ...files(LIVE, (p) => p.endsWith(".ts"))];
const referencedFixtures = new Set<string>();
for (const file of testFiles) {
  const source = readFileSync(file, "utf8");
  const code = codeOnly(source);
  if (file.endsWith(".cli-test.ts") || file.endsWith(".ts")) {
    for (const m of source.matchAll(/^\s*import\s[^;]*?from\s+"([^"]+)"/gm)) {
      if (!/^\.\.\/sdk$|^\.\.\/sdk\/index(\.ts)?$|^\.\//.test(m[1])) {
        report(file, lineOf(source, m.index!), `Tests import only from "../sdk" (or local helpers); found "${m[1]}".`);
      }
    }
  }
  forbid(file, code, /\bsetTimeout\b|\bBun\.sleep\b|\bsleep\(|waitFor\w*\(/, "No sleeps or wait helpers in tests: do()/precondition() poll for you.");
  forbid(file, code, /\btest\.only\b|\bdescribe\.only\b/, "Remove .only before committing.");
  forbid(file, code, /\bmock\(|jest\.|spyOn\(/, "No mocking. Swap behavior with fixtures.");
  for (const m of source.matchAll(/fixture\("([^"]+)"\)/g)) referencedFixtures.add(m[1]);
}

// 8. Fixtures are valid and referenced fixtures exist ---------------------------------------------------
const fixtureNames = new Set<string>();
for (const file of files(FIXTURES, (p) => p.endsWith(".json"))) {
  const name = relative(FIXTURES, file).replace(/\.json$/, "").split("/").join(".");
  fixtureNames.add(name);
  let json: any;
  try {
    json = JSON.parse(readFileSync(file, "utf8"));
  } catch (e) {
    report(file, null, `Fixture is not valid JSON: ${(e as Error).message}`);
    continue;
  }
  for (const key of Object.keys(json)) {
    if (!["extends", "graphql", "storage", "device", "flags"].includes(key)) report(file, null, `Fixture: unknown key "${key}".`);
  }
  for (const [i, entry] of (json.graphql ?? []).entries()) {
    if (typeof entry.operation !== "string") report(file, null, `Fixture: graphql[${i}] needs an "operation".`);
    else if (!swiftOps.has(entry.operation)) report(file, null, `Fixture: graphql[${i}] operation "${entry.operation}" doesn't exist in Core.`);
    if (("response" in entry) === ("error" in entry)) report(file, null, `Fixture: graphql[${i}] needs exactly one of "response" or "error".`);
  }
}
for (const file of files(FIXTURES, (p) => p.endsWith(".json"))) {
  const json = JSON.parse(readFileSync(file, "utf8"));
  for (const parent of json.extends ?? []) if (!fixtureNames.has(parent)) report(file, null, `Fixture: extends unknown fixture "${parent}".`);
}
for (const name of referencedFixtures) if (!fixtureNames.has(name)) report(BEHAVIOR, null, `Tests reference missing fixture "${name}".`);

// ------------------------------------------------------------------------------------------------------
if (problems.length) {
  console.log(`reflex check: ${problems.length} problem(s)\n`);
  for (const p of problems) console.log(`  ${p}`);
  process.exit(1);
}
console.log(`reflex check: ok (${swiftSurface.size} nodes, ${swiftOps.size} operations, ${fixtureNames.size} fixtures, ${testFiles.length} test files)`);
