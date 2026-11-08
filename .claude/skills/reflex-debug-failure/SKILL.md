---
name: reflex-debug-failure
description: Systematic procedure for diagnosing a failing Reflex behavior test from its failure message (step, action, expected vs observed, last state, available actions), plus host build/start failures, fixture misses, network violations and timeouts. Use whenever ./reflex test or ./reflex ci fails.
---

# Debugging a failing behavior test

A failure message is designed to be enough on its own. Read it in this order before touching code:

```
iOS — step 5: do("SetCount", {"count":40})                  ← 1. platform, step number, action + payload
Focused: SampleDetail  (App › StackNavigator › SampleDetail)  ← 2. where the user was
  Expected: 40                                               ← 3. expected vs observed
  Received: 0
Last state (after 2003ms, 20 polls):                         ← 4. full state after the timeout
  {"itemId": "1", "count": 0, ...}
Available actions: Increment, Decrement, SetCount {count}, Save, goBack
Previous steps: precondition(SampleList) ✓ → SelectItem ✓ → … ← 5. how far the journey got
```

## 1. Classify

| Symptom | Meaning | Go to |
|---|---|---|
| Fails on **one** platform only | the two implementations disagree | `reflex-parity` |
| `Fixture miss: no fixture for operation 'X' …` | the journey reached an operation the session's fixtures don't cover | add the entry (`reflex-add-fixture`) or fix the variables the ViewModel sends |
| `Network violation: …` | code bypassed `GraphQLTransport` (a direct URLSession/HttpURLConnection call in Core or a service) | route it through an edge (`reflex-add-edge`) |
| `ACTION_NOT_AVAILABLE` | the focused screen isn't what the test assumes (navigation didn't happen/happened twice), or the node lacks the action | check `Focused:` and the previous steps; check node coverage (`./reflex check`) |
| `INVALID_PAYLOAD` | test payload doesn't match the input type | fix the test payload or the `…Input` type (same field names on both platforms) |
| Timeout, **state never changed** | the action didn't reach `update`, the case is missing/guarded, or the async result never came back as a Message | step 2 |
| Timeout, **state changed but differs** | the business rule is wrong (or the test expectation is) | step 2 |
| `host not built` / `host exited` | build failure or a crash at startup; stderr is included | `./reflex build <platform>` and read the compiler error |
| `session ended with violations` | a miss/violation arrived after the last step | as fixture miss / network violation |

## 2. Reproduce outside the test

Replay the steps with the CLI on the failing platform and watch the state after each one:

```
printf 'ls\ndo SelectItem {"id":"1"}\nls\ndo SetCount {"count":40}\nls\ntree\n' | ./reflex cli ios --fixture Sample.default
```

Then read the ViewModel's `update` for the Message the action produces (`viewAction(.x)`), and for async paths
the `Task`/`scope.launch` that should send the result Message.

## 3. Fix the cause, not the symptom

- Fix behavior in the ViewModel's `update` (or its service/fixture), on **both** platforms.
- Don't raise `timeoutMs` to make a test pass; fixture responses resolve in milliseconds. A timeout means the
  expected state never arrives.
- Don't change expectations unless the test is wrong for both platforms and for the product.
- Re-run the single file on both platforms, then `./reflex ci`.
