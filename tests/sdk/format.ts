// Human-readable rendering shared by the CLI REPL and test failure messages.

import type { ActionInfo, JsonSchema, NodeSnapshot, ProtocolError, TreeNode } from "./protocol";

/** One-line JSON with a space after ':' and ',' — `{"itemId": "1", "count": 2}`. */
export function inlineJson(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value) ?? "undefined";
  if (Array.isArray(value)) return `[${value.map(inlineJson).join(", ")}]`;
  return `{${Object.entries(value).map(([k, v]) => `${JSON.stringify(k)}: ${inlineJson(v)}`).join(", ")}}`;
}

/** `SetCount {count}` for actions with input, plain title otherwise. */
export function actionLabel(action: ActionInfo): string {
  const fields = Object.keys(action.input?.properties ?? {});
  return action.input ? `${action.title} {${fields.join(", ")}}` : action.title;
}

export function actionList(actions: ActionInfo[]): string {
  return actions.map(actionLabel).join(", ");
}

/**
 *     SampleDetail
 *       state   {"itemId": "1", "count": 2, ...}
 *       actions Increment, Decrement, SetCount {count}, Save, goBack
 */
export function formatNode(node: NodeSnapshot): string {
  return `${node.name}\n  state   ${inlineJson(node.state)}\n  actions ${actionList(node.actions)}`;
}

export function formatTree(tree: TreeNode[]): string {
  return tree
    .map((node, depth) => {
      const pad = "  ".repeat(depth);
      const actions = node.actions.map((a) => (a.scope === "lent" ? `${actionLabel(a)} (lent)` : actionLabel(a)));
      return `${pad}${node.name}\n${pad}  state   ${inlineJson(node.state)}\n${pad}  actions ${actions.join(", ") || "—"}`;
    })
    .join("\n");
}

/** `{"count": integer, "note": string|null}` */
export function schemaSummary(schema: JsonSchema | undefined): string {
  if (!schema) return "none";
  const describe = (s: JsonSchema): string => {
    if (s.enum) return s.enum.map((e) => JSON.stringify(e)).join("|");
    if (s.type === "object" || s.properties) return schemaSummary(s);
    if (s.type === "array") return `[${s.items ? describe(s.items) : "any"}]`;
    return Array.isArray(s.type) ? s.type.join("|") : (s.type ?? "any");
  };
  const entries = Object.entries(schema.properties ?? {}).map(([k, v]) => `${JSON.stringify(k)}: ${describe(v)}`);
  return `{${entries.join(", ")}}`;
}

/** One-line CLI error, with the available actions when the focused node is known. */
export function formatError(error: ProtocolError, focused?: NodeSnapshot): string {
  switch (error.code) {
    case "ACTION_NOT_AVAILABLE": {
      const available = focused ? actionList(focused.actions) : (error.available ?? []).join(", ");
      return `error: ${error.message}. Available: ${available}`;
    }
    case "INVALID_PAYLOAD":
      return `error: ${error.message}. Schema: ${schemaSummary(error.schema)}`;
    default:
      return `error: ${error.code}: ${error.message}`;
  }
}
