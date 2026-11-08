// Reflex NDJSON protocol, version 1. The hosts (ios/AppCLI, android/cli, and the DEBUG socket servers)
// implement exactly this; see ReflexHost.swift / ReflexHost.kt.

export type Platform = "ios" | "android";
export const PLATFORMS: readonly Platform[] = ["ios", "android"];
export const PLATFORM_LABEL: Record<Platform, string> = { ios: "iOS", android: "Android" };

export interface ActionInfo {
  title: string;
  /** JSON Schema of the payload, when the action takes input. */
  input?: JsonSchema;
  /** The ancestor (e.g. "StackNavigator") that lent this action to the focused node. */
  lentBy?: string;
}

export interface NodeSnapshot {
  name: string;
  path: string[];
  state: Record<string, unknown>;
  actions: ActionInfo[];
}

export interface TreeNode {
  name: string;
  state: Record<string, unknown>;
  actions: (ActionInfo & { scope?: "lent" })[];
}

export interface JsonSchema {
  type?: string | string[];
  properties?: Record<string, JsonSchema>;
  required?: string[];
  items?: JsonSchema;
  enum?: unknown[];
  additionalProperties?: boolean;
}

export type ErrorCode =
  | "ACTION_NOT_AVAILABLE"
  | "INVALID_PAYLOAD"
  | "UNKNOWN_SESSION"
  | "FIXTURE_NOT_FOUND"
  | "INVALID_FIXTURE"
  | "BAD_REQUEST"
  | "UNSUPPORTED"
  | "INTERNAL";

export interface ProtocolError {
  code: ErrorCode;
  message: string;
  available?: string[];
  schema?: JsonSchema;
}

export type Request =
  | { cmd: "session.create"; fixtures: string[]; deeplink?: string | null }
  | { cmd: "ls"; session: string }
  | { cmd: "tree"; session: string }
  | { cmd: "do"; session: string; action: string; payload?: unknown }
  | { cmd: "session.close"; session: string };

export type Response =
  | { id: number; ok: true; session?: string; node?: NodeSnapshot; tree?: TreeNode[] }
  | { id: number; ok: false; error: ProtocolError };

export type HostEvent =
  | { event: "ready"; platform: Platform; protocol: number }
  | { event: "network_violation"; session: string | null; request: { method: string; url: string } }
  | { event: "fixture_miss"; session: string; operation: string; variables: Record<string, unknown> }
  | { event: "log"; session: string; level: string; message: string };

export class ReflexProtocolError extends Error {
  constructor(readonly error: ProtocolError) {
    super(`${error.code}: ${error.message}`);
  }
}
