#!/usr/bin/env bash
# Claude Code SessionStart hook (wired in .claude/settings.json): the toolchain check is a required first
# step on every machine. Its result lives in .reflex/toolchain.local.json (gitignored).
record="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}/.reflex/toolchain.local.json"
if [[ -f "$record" ]]; then
  checked="$(sed -n 's/.*"checkedAt": "\(.*\)".*/\1/p' "$record")"
  echo "Reflex toolchain verified on this machine at ${checked} (.reflex/toolchain.local.json). Re-check only if a tool breaks: ./reflex setup --force"
else
  cat <<'MSG'
REQUIRED FIRST STEP: this machine's Reflex toolchain has not been verified (.reflex/toolchain.local.json is missing).
Before building, testing, running or editing app code, run `./reflex setup`. It checks Xcode, Swift, Bun, JDK 21,
XcodeGen and the Android SDK, installs anything missing via Homebrew, and records the result. Tell the user it is
running (installs can take minutes). If it reports a tool it cannot install (e.g. Xcode), stop and ask the user.
MSG
fi
