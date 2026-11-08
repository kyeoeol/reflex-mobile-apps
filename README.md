# Reflex mobile apps

iOS and Android apps built to explore Shopify's Reflex architecture, as introduced in [Reflex: an architecture for building mobile apps with agents](https://shopify.engineering/reflex).

- **Native twice, tested once.** Swift/SwiftUI and Kotlin/Compose, no shared code. One Bun test suite drives both.
- **Headless core.** ViewModels, services, navigation and deep links live in a core with no UI imports that runs on
  macOS / the plain JVM. Behavior tests need no simulator or emulator.
- **Introspection.** Every screen is exposed as JSON (name · state · actions), so tests, agents and the CLI drive the
  app exactly the way a user would.

The product itself is not decided yet; a small `Sample` feature (list → detail) shows every part working end to end.

## Getting started

```sh
./reflex setup      # once per machine: checks/installs Xcode tools, Bun, JDK 21, XcodeGen, Android SDK
./reflex test       # behavior tests on iOS and Android (headless)
./reflex cli ios --fixture Sample.default    # then type: ls · do SelectItem {"id": "1"} · tree · quit
./reflex ci         # lint + unit + behavior tests (the merge gate)
./reflex help       # everything else
```

How the codebase is organized and the rules it follows are in [CLAUDE.md](CLAUDE.md); step-by-step procedures
(new feature, new screen, fixtures, tests, debugging, parity) are in [.claude/skills](.claude/skills).
