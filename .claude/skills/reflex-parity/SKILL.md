---
name: reflex-parity
description: Procedure for when a Reflex behavior test passes on one platform (iOS or Android) and fails on the other. Covers comparing the two update reducers, node state mappings, input schemas, GraphQL operation names and variables, fixture adapters, and async ordering, then fixing the divergent implementation without touching the shared test. Use for any iOS/Android behavior mismatch.
---

# Fixing a parity failure

The test is the specification; the platform that fails it is wrong (unless the test is wrong for both). Never add
platform branches to a test, and never "fix" it by loosening the expectation.

## 1. Diff the observable surface first

Run the same script on both platforms in JSON mode and diff:

```
S='ls\ndo SelectItem {"id":"1"}\nls\ndo Save\nls\n'
diff <(printf "$S" | ./reflex cli ios --fixture X.y --json) <(printf "$S" | ./reflex cli android --fixture X.y --json)
```

(Re-run if a diff is only due to an `ls` racing an async result; then compare the settled state.)
`./reflex check` also reports node-name, action-list and operation-name mismatches statically.

## 2. Compare, side by side, in this order

1. **`update`**: same Messages handled, same guards (`where count > 0` ↔ `if (state.count > 0)`), same fields
   reset, same literal strings. The usual culprit.
2. **Initial state**: Swift `State` defaults vs Kotlin data-class defaults (`isLoading = true` on one only?).
3. **Async paths**: both send the same result Message on success *and* failure; Kotlin uses `attempt` (not a
   swallowed exception); Swift's `Task` sends back via `self.send(message:)`.
4. **Node mapping**: same keys, same order, same types (`.number(Int)` vs `JsonValue.Num`), `null` vs missing (use
   `JSONValue(x)` / `JsonValue.of(x)`), derived fields computed identically.
5. **Inputs**: same field names and optionality (`T?` ↔ `T?` without default).
6. **Edges**: same `operationName`, same variable keys (fixtures match on them), same decoding of the response
   (Kotlin `ignoreUnknownKeys`; Swift `Decodable` keys), same fixture-adapter defaults in both CLIs.
7. **Navigation**: same Route pushes, same `AppRoot.open` parsing, same `goBack` semantics.
8. **Numbers/strings**: integer vs floating math, rounding, string formatting (format in the ViewModel with
   explicit rules, not locale defaults).

## 3. Fix and verify

Fix the divergent platform (or both, if the shared intent was unclear: decide the behavior, then make both match).
Then run the file on both platforms, `./reflex check`, and finally `./reflex ci`.
