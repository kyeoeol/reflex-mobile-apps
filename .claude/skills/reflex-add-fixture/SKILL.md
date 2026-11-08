---
name: reflex-add-fixture
description: Fixture files for Reflex behavior tests and the headless CLI. Covers where they live, the JSON format, extends and merge rules, GraphQL operation matching and consumption order, simulating errors, device/flags/storage, and fixing fixture_miss or network_violation. Use when adding or changing test data, or when a test reports a fixture miss.
---

# Fixtures

Every input that normally comes from outside the process is a fixture, passed to `Core(...)` at session start.
Both platforms load the same files, so they are checked to produce the same state from the same inputs.

## Location and naming

`fixtures/<Domain>/<scenario>.json` → referenced as `fixture("Domain.scenario")` in tests and
`--fixture Domain.scenario` in the CLI. Domain = the feature (PascalCase), scenario = camelCase
(`default`, `saveFails`, `empty`, `listFailsOnce`, `bulkEditEnabled`).

## Format

```jsonc
{
  "extends": ["Sample.default"],            // optional; merged first, in order
  "graphql": [
    {"operation": "SampleItem", "variables": {"id": "1"},            // variables: partial match; omit = any
     "response": {"data": {"item": {"id": "1", "title": "First item", "count": 2}}}},
    {"operation": "SampleSave", "response": {"errors": [{"message": "boom"}]}},   // a GraphQL error
    {"operation": "SampleSave", "error": {"kind": "http", "status": 500}},       // transport errors:
    {"operation": "SampleSave", "error": {"kind": "network"}}                    //   http | network
  ],
  "storage": {"session.token": "fixture-token"},   // string (or null) values
  "device":  {"biometricsAvailable": false},       // missing keys default to false
  "flags":   {"sample.bulkEdit": true}             // missing flags are disabled
}
```

Only these five top-level keys are allowed; anything else is an `INVALID_FIXTURE` error.

## Rules

- **Merge**: fixtures listed later (or the file itself after its `extends`) win. For GraphQL, if a later fixture has
  *any* entry for operation X, it replaces *all* earlier entries for X, so an override restates every entry it
  needs for that operation. `storage`/`device`/`flags` merge key by key.
- **Matching**: same `operation`, and every listed variable equals the request's.
- **Consumption**: entries matching a request are used in declaration order; the last one repeats forever.
  `[error, response]` = fails once, then succeeds (see `fixtures/Sample/listFailsOnce.json`).
- **No match** → a `fixture_miss` event and the test fails, even if the app handled the error. A real network attempt
  → `network_violation`, same result. Treat both as "a fixture is missing", never as noise.

## Workflow

1. Start from the domain's `default.json` (the happy path for every operation its journeys reach).
2. One scenario file per *different setup* a test needs, usually `extends` + the one override.
3. Find which operations a journey reaches: run it through the CLI and watch for misses:
   `printf 'ls\ndo SelectItem {"id":"1"}\nls\n' | ./reflex cli ios --fixture Sample.default` prints
   `! fixture_miss: <Operation> {variables}` lines.
4. `./reflex check` validates every fixture (JSON, keys, `extends` targets, operations that exist in Core, and that
   every `fixture("…")` used by tests exists).

Fixtures get big (a journey can need a dozen responses). That's expected; keep them realistic and minimal per
scenario rather than sharing one giant file with conditional data.
