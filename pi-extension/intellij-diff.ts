import {
  createEditToolDefinition,
  createWriteToolDefinition,
  isToolCallEventType,
  type EditOperations,
  type ExtensionAPI,
  type ExtensionContext,
  type WriteOperations,
} from "@earendil-works/pi-coding-agent";
import { randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { access, mkdir, readFile, readdir, stat, unlink, writeFile } from "node:fs/promises";
import { dirname, relative, resolve } from "node:path";

const DEFAULT_PORT = 63345;

type Config = { port: number; token: string };
type MutationKind = "edit" | "write" | "delete";
type ReviewMode = "pre-apply" | "post-prompt-review";
type IdeSettings = {
  approveCreates: boolean;
  approveEdits: boolean;
  approveDeletes: boolean;
  reviewMode: ReviewMode;
};
type Baseline = { before: string; kind: MutationKind; existedBefore: boolean };
type ReviewDecision = { path: string; action: "accept" | "reject" | "requestChanges"; reason?: string };
type ReviewResult = { action: "accept" | "mixed" | "requestChanges"; request?: string; decisions?: ReviewDecision[] };

export default function (pi: ExtensionAPI) {
  const baselines = new Map<string, Baseline>();
  let reviewing = false;

  pi.registerFlag("intellij-diff-port", { description: "Port for the Pi IntelliJ Diff plugin approval server", type: "string" });
  pi.registerFlag("intellij-diff-token", { description: "Bearer token for the Pi IntelliJ Diff plugin approval server", type: "string" });

  function getConfig(): Config {
    return {
      port: Number(pi.getFlag("intellij-diff-port") || process.env.PI_INTELLIJ_DIFF_PORT || DEFAULT_PORT),
      token: String(pi.getFlag("intellij-diff-token") || process.env.PI_INTELLIJ_DIFF_TOKEN || ""),
    };
  }

  function headers(config: Config): Record<string, string> {
    return { "content-type": "application/json", ...(config.token ? { authorization: `Bearer ${config.token}` } : {}) };
  }

  async function fetchSettings(signal?: AbortSignal): Promise<IdeSettings> {
    const config = getConfig();
    const response = await fetch(`http://127.0.0.1:${config.port}/api/pi/settings`, { method: "GET", headers: headers(config), signal });
    if (!response.ok) throw new Error(`HTTP ${response.status} ${await response.text()}`);
    const settings = (await response.json()) as Partial<IdeSettings>;
    return {
      approveCreates: settings.approveCreates ?? true,
      approveEdits: settings.approveEdits ?? true,
      approveDeletes: settings.approveDeletes ?? true,
      reviewMode: settings.reviewMode ?? "pre-apply",
    };
  }

  function enabled(settings: IdeSettings, kind: MutationKind): boolean {
    if (kind === "write") return settings.approveCreates;
    if (kind === "edit") return settings.approveEdits;
    return settings.approveDeletes;
  }

  async function checkHealth(config: Config, signal?: AbortSignal) {
    const response = await fetch(`http://127.0.0.1:${config.port}/api/pi/health`, { method: "GET", headers: headers(config), signal });
    if (!response.ok) throw new Error(`HTTP ${response.status} ${await response.text()}`);
    return (await response.json()) as { ok?: boolean; port?: number; tokenRequired?: boolean };
  }

  async function fetchIdeContext(ctx: ExtensionContext, consumeFocus = false) {
    const config = getConfig();
    const response = await fetch(`http://127.0.0.1:${config.port}/api/pi/context`, {
      method: "POST",
      headers: headers(config),
      body: JSON.stringify({ cwd: ctx.cwd, consumeFocus }),
      signal: ctx.signal,
    });
    if (!response.ok) return undefined;
    return (await response.json()) as {
      hasSelection?: boolean;
      activeFile?: string;
      activeLanguage?: string;
      focusFiles?: string[];
      path?: string;
      startLine?: number;
      endLine?: number;
      language?: string;
      text?: string;
      truncated?: boolean;
    };
  }

  async function approve(ctx: ExtensionContext, path: string, before: string, after: string, kind: MutationKind) {
    if (before === after) return;
    if (!ctx.hasUI && !process.env.PI_INTELLIJ_DIFF_ALLOW_HEADLESS) {
      throw new Error("IntelliJ diff approval requires interactive/RPC mode or PI_INTELLIJ_DIFF_ALLOW_HEADLESS=1.");
    }
    const config = getConfig();
    let response: Response;
    try {
      response = await fetch(`http://127.0.0.1:${config.port}/api/pi/diff`, {
        method: "POST",
        headers: headers(config),
        body: JSON.stringify({ requestId: randomUUID(), cwd: ctx.cwd, path, displayPath: relative(ctx.cwd, path) || path, kind, before, after }),
        signal: ctx.signal,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new Error(`Could not contact IntelliJ diff approval server on 127.0.0.1:${config.port}. ${message}`);
    }
    if (!response.ok) throw new Error(`IntelliJ diff approval failed: HTTP ${response.status} ${await response.text()}`);
    const result = (await response.json()) as { accepted?: boolean; reason?: string };
    if (!result.accepted) throw new Error(result.reason || "User rejected the change in IntelliJ.");
  }

  async function recordBaseline(path: string, before: string, kind: MutationKind, existedBefore: boolean) {
    if (!baselines.has(path)) baselines.set(path, { before, kind, existedBefore });
  }

  async function restoreBaseline(path: string, baseline: Baseline) {
    if (!baseline.existedBefore) {
      try { await unlink(path); } catch {}
      return;
    }
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, baseline.before, "utf8");
  }

  async function restoreBaselines() {
    for (const [path, baseline] of baselines) await restoreBaseline(path, baseline);
    baselines.clear();
  }

  async function maybePreApproveOrRecord(ctx: ExtensionContext, path: string, before: string, after: string, kind: MutationKind, existedBefore: boolean) {
    const settings = await fetchSettings(ctx.signal);
    if (!enabled(settings, kind)) return;
    if (settings.reviewMode === "post-prompt-review") {
      await recordBaseline(path, before, kind, existedBefore);
    } else {
      await approve(ctx, path, before, after, kind);
    }
  }

  async function postPromptReview(ctx: ExtensionContext) {
    if (reviewing || baselines.size === 0) return;
    const settings = await fetchSettings(ctx.signal).catch(() => undefined);
    if (!settings || settings.reviewMode !== "post-prompt-review") return;

    const files = [] as Array<{ path: string; displayPath: string; before: string; after: string; kind: MutationKind; existedBefore: boolean }>;
    for (const [path, baseline] of baselines) {
      let after = "";
      let existsAfter = true;
      try { after = await readFile(path, "utf8"); } catch { after = ""; existsAfter = false; }
      if (baseline.before !== after || baseline.existedBefore !== existsAfter) {
        files.push({ path, displayPath: relative(ctx.cwd, path) || path, before: baseline.before, after, kind: baseline.kind, existedBefore: baseline.existedBefore });
      }
    }
    if (files.length === 0) {
      baselines.clear();
      return;
    }

    reviewing = true;
    try {
      const config = getConfig();
      const response = await fetch(`http://127.0.0.1:${config.port}/api/pi/review`, {
        method: "POST",
        headers: headers(config),
        body: JSON.stringify({ cwd: ctx.cwd, files }),
        signal: ctx.signal,
      });
      if (!response.ok) throw new Error(`HTTP ${response.status} ${await response.text()}`);
      const result = (await response.json()) as ReviewResult;
      const decisions = result.decisions?.length
        ? result.decisions
        : files.map((file) => ({ path: file.path, action: result.action === "accept" ? "accept" : "reject" }) as ReviewDecision);

      const accepted: ReviewDecision[] = [];
      const rejected: ReviewDecision[] = [];
      const needsChanges: ReviewDecision[] = [];
      for (const decision of decisions) {
        if (decision.action === "accept") accepted.push(decision);
        else if (decision.action === "reject") rejected.push(decision);
        else needsChanges.push(decision);
      }

      for (const decision of rejected) {
        const baseline = baselines.get(decision.path);
        if (baseline) {
          await restoreBaseline(decision.path, baseline);
          baselines.delete(decision.path);
        }
      }
      for (const decision of accepted) baselines.delete(decision.path);

      if (needsChanges.length === 0) return;

      const request = [
        result.request?.trim(),
        rejected.length
          ? `Rejected and restored:\n${rejected.map((d) => `- ${relative(ctx.cwd, d.path) || d.path}${d.reason ? `\n  Reason: ${d.reason}` : ""}`).join("\n")}`
          : "",
        needsChanges.length
          ? `Needs revision, keeping original baseline for future comparison:\n${needsChanges.map((d) => `- ${relative(ctx.cwd, d.path) || d.path}${d.reason ? `\n  Reason: ${d.reason}` : ""}`).join("\n")}`
          : "",
        accepted.length
          ? `Accepted:\n${accepted.map((d) => `- ${relative(ctx.cwd, d.path) || d.path}`).join("\n")}`
          : "",
      ].filter(Boolean).join("\n\n") || "Please revise the previous proposal.";

      pi.sendUserMessage(
        `The user reviewed your proposed file changes.\n\n${request}\n\n` +
          `For files marked as needing revision, keep the same original baseline. If satisfying this request requires modifying additional files, do so.`,
        { deliverAs: "followUp" },
      );
    } finally {
      reviewing = false;
    }
  }

  function tokenizeShell(command: string): string[] {
    const tokens: string[] = [];
    let current = "";
    let quote: "'" | '"' | undefined;
    let escaped = false;
    for (const ch of command) {
      if (escaped) { current += ch; escaped = false; continue; }
      if (ch === "\\" && quote !== "'") { escaped = true; continue; }
      if (quote) { if (ch === quote) quote = undefined; else current += ch; continue; }
      if (ch === "'" || ch === '"') { quote = ch; continue; }
      if (/\s/.test(ch)) { if (current) { tokens.push(current); current = ""; } continue; }
      current += ch;
    }
    if (current) tokens.push(current);
    return tokens;
  }

  function deletionTargets(command: string): string[] {
    const targets: string[] = [];
    for (const part of command.split(/\s*(?:&&|;)\s*|\r?\n/g).map((p) => p.trim()).filter(Boolean)) {
      const tokens = tokenizeShell(part);
      if (tokens[0] !== "rm" && tokens[0] !== "unlink") continue;
      let endOfOptions = false;
      for (const token of tokens.slice(1)) {
        if (!endOfOptions && token === "--") { endOfOptions = true; continue; }
        if (!endOfOptions && token.startsWith("-")) continue;
        targets.push(token);
      }
    }
    return targets;
  }

  async function collectFiles(path: string): Promise<string[]> {
    const info = await stat(path);
    if (info.isFile()) return [path];
    if (!info.isDirectory()) return [];
    const entries = await readdir(path, { withFileTypes: true });
    const files: string[] = [];
    for (const entry of entries) {
      const child = resolve(path, entry.name);
      if (entry.isDirectory()) files.push(...(await collectFiles(child)));
      else if (entry.isFile()) files.push(child);
    }
    return files;
  }

  async function handleDeletionsFromBash(ctx: ExtensionContext, command: string) {
    const settings = await fetchSettings(ctx.signal);
    if (!enabled(settings, "delete")) return;
    for (const target of deletionTargets(command)) {
      const absolutePath = resolve(ctx.cwd, target);
      let files: string[] = [];
      try { files = await collectFiles(absolutePath); } catch { continue; }
      if (files.length > 50) throw new Error(`Refusing to approve deletion of ${files.length} files from ${target}. Delete fewer files at once.`);
      for (const file of files) {
        const before = await readFile(file, "utf8");
        if (settings.reviewMode === "post-prompt-review") await recordBaseline(file, before, "delete", true);
        else await approve(ctx, file, before, "", "delete");
      }
    }
  }

  function formatIdeContextForPrompt(ctx: ExtensionContext, ideContext: Awaited<ReturnType<typeof fetchIdeContext>>): string | undefined {
    if (!ideContext) return undefined;
    const blocks: string[] = [];
    if (ideContext.focusFiles?.length) {
      blocks.push(`The user explicitly marked these IntelliJ project files as focused context for this prompt. Read file contents with the read tool when needed.\n` + ideContext.focusFiles.map((file) => `- ${relative(ctx.cwd, file) || file}`).join("\n"));
    }
    if (ideContext.activeFile) {
      blocks.push(`The currently active IntelliJ editor tab is: ${relative(ctx.cwd, ideContext.activeFile) || ideContext.activeFile}. If the prompt says "this file" and no explicit focused files are listed above, it refers to this active file. Read file contents with the read tool when needed.`);
    }
    if (ideContext.hasSelection && ideContext.text) {
      const path = ideContext.path ? relative(ctx.cwd, ideContext.path) || ideContext.path : "unknown file";
      const language = ideContext.language || "text";
      const lines = ideContext.startLine && ideContext.endLine ? ` lines ${ideContext.startLine}-${ideContext.endLine}` : "";
      const truncated = ideContext.truncated ? "\n[Selection truncated to 20,000 characters.]" : "";
      blocks.push(`The user currently has this code selected in the active IntelliJ editor tab.\n\nFile: ${path}${lines}\n\n` + "```" + language + "\n" + ideContext.text + "\n```" + truncated);
    }
    return blocks.length ? blocks.join("\n\n") : undefined;
  }

  pi.on("input", async (event, ctx) => {
    if (event.source === "extension" || event.text.trimStart().startsWith("/")) return { action: "continue" };
    let ideContext: Awaited<ReturnType<typeof fetchIdeContext>> | undefined;
    try { ideContext = await fetchIdeContext(ctx, true); } catch { return { action: "continue" }; }
    const ideContextText = formatIdeContextForPrompt(ctx, ideContext);
    if (!ideContextText) return { action: "continue" };
    return { action: "transform", text: `${ideContextText}\n\nUser prompt:\n${event.text}`, images: event.images };
  });

  pi.registerCommand("intellij-selection-status", {
    description: "Show whether IntelliJ currently reports active editor/project context",
    handler: async (_args, ctx) => {
      try {
        const ideContext = await fetchIdeContext(ctx, false);
        const ideContextText = formatIdeContextForPrompt(ctx, ideContext);
        ctx.ui.notify(ideContextText ? ideContextText.slice(0, 4000) : "IntelliJ reports no active editor context.", ideContextText ? "info" : "warning");
      } catch (error) {
        ctx.ui.notify(`Could not fetch IntelliJ selection: ${error instanceof Error ? error.message : String(error)}`, "error");
      }
    },
  });

  pi.registerCommand("intellij-diff-status", {
    description: "Check connection to the IntelliJ diff approval plugin",
    handler: async (_args, ctx) => {
      const config = getConfig();
      try {
        const health = await checkHealth(config, ctx.signal);
        const settings = await fetchSettings(ctx.signal);
        ctx.ui.notify(`IntelliJ diff approval server is reachable on port ${health.port ?? config.port}. Mode: ${settings.reviewMode}. Creates=${settings.approveCreates}, edits=${settings.approveEdits}, deletes=${settings.approveDeletes}.`, "info");
      } catch (error) {
        ctx.ui.notify(`IntelliJ diff approval server is not reachable: ${error instanceof Error ? error.message : String(error)}`, "error");
      }
    },
  });

  pi.on("tool_call", async (event, ctx) => {
    if (!isToolCallEventType("bash", event)) return;
    if (deletionTargets(event.input.command).length === 0) return;
    try { await handleDeletionsFromBash(ctx, event.input.command); }
    catch (error) { return { block: true, reason: error instanceof Error ? error.message : String(error) }; }
  });

  pi.on("agent_end", async (_event, ctx) => { await postPromptReview(ctx); });

  pi.on("session_start", (_event, ctx) => {
    const config = getConfig();
    const editOps: EditOperations = {
      readFile: (path) => readFile(path),
      access: (path) => access(path, constants.R_OK | constants.W_OK),
      async writeFile(path, content) {
        const before = await readFile(path, "utf8");
        await maybePreApproveOrRecord(ctx, path, before, content, "edit", true);
        await writeFile(path, content, "utf8");
      },
    };
    const writeOps: WriteOperations = {
      mkdir: (dir) => mkdir(dir, { recursive: true }).then(() => undefined),
      async writeFile(path, content) {
        let before = "";
        let kind: MutationKind = "write";
        try { before = await readFile(path, "utf8"); kind = "edit"; } catch { before = ""; kind = "write"; }
        await maybePreApproveOrRecord(ctx, path, before, content, kind, kind === "edit");
        await mkdir(dirname(path), { recursive: true });
        await writeFile(path, content, "utf8");
      },
    };
    pi.registerTool(createEditToolDefinition(ctx.cwd, { operations: editOps }));
    pi.registerTool(createWriteToolDefinition(ctx.cwd, { operations: writeOps }));
    ctx.ui.setStatus("intellij-diff", `IntelliJ diff approvals: :${config.port}`);
  });
}
