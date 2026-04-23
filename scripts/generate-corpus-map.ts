import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { execSync } from "child_process";
import matter from "gray-matter";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const OUT = path.join(ROOT, "docs", ".corpus-map.md");

type MdEntry = {
  relPath: string;
  name: string;
  title: string;
  date?: string;
  status?: string;
  description: string;
};

type CodeEntry = {
  relPath: string;
  description: string;
};

function walk(dir: string, exts: string[]): string[] {
  if (!fs.existsSync(dir)) return [];
  const out: string[] = [];
  const stack = [dir];
  while (stack.length) {
    const cur = stack.pop()!;
    for (const name of fs.readdirSync(cur)) {
      if (name.startsWith(".") || name === "node_modules" || name === "build" || name === "dist") continue;
      const full = path.join(cur, name);
      const st = fs.statSync(full);
      if (st.isDirectory()) stack.push(full);
      else if (exts.some((e) => name.endsWith(e))) out.push(full);
    }
  }
  return out.sort();
}

function oneLine(s: string, limit = 180): string {
  const collapsed = s.replace(/\s+/g, " ").trim();
  return collapsed.length > limit ? collapsed.slice(0, limit - 1) + "…" : collapsed;
}

function firstParagraph(body: string): string {
  // Разбиваем на параграфы, отбрасываем заголовки и bullet-списки метаданных
  // (Статус/Дата/Авторы/Теги — такие блоки в ADR и блоге выглядят как параграф из «- ...»).
  const paragraphs = body.split(/\n\n+/);
  for (const p of paragraphs) {
    const trimmed = p.trim();
    if (!trimmed) continue;
    if (trimmed.startsWith("#")) continue;
    const lines = trimmed.split("\n").map((l) => l.trim());
    const allBullets = lines.every((l) => l.startsWith("-") || l.startsWith("*"));
    if (allBullets) continue;
    return lines
      .filter((l) => !l.startsWith("#"))
      .join(" ")
      .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
      .replace(/[*_`]+/g, "")
      .trim();
  }
  return "";
}

function firstHeading(body: string): string {
  const m = body.match(/^#\s+(.+)$/m);
  return m ? m[1].trim() : "";
}

function readMdEntry(abs: string): MdEntry {
  const raw = fs.readFileSync(abs, "utf-8");
  const parsed = matter(raw);
  const data = parsed.data as Record<string, unknown>;
  const relPath = path.relative(ROOT, abs);
  const name = path.basename(abs);
  const title = (data.title as string) || (data.name as string) || firstHeading(parsed.content) || name;
  const date = (data.date as string) || undefined;
  const status = (data.status as string) || undefined;
  const description = oneLine(firstParagraph(parsed.content));
  return { relPath, name, title, date, status, description };
}

/**
 * Достаёт первый KDoc/JSDoc-блок, описывающий верхнеуровневую сущность файла.
 * Если докблока нет — падает на первую непустую значимую строку.
 */
function extractFirstDocComment(source: string): string {
  const docMatch = source.match(/\/\*\*([\s\S]*?)\*\//);
  if (docMatch) {
    const text = docMatch[1]
      .split("\n")
      .map((l) => l.replace(/^\s*\*\s?/, "").trim())
      .filter(Boolean)
      .join(" ")
      .replace(/@\w+/g, "")
      .trim();
    if (text) return oneLine(text);
  }
  const firstLine = source
    .split("\n")
    .map((l) => l.trim())
    .find(
      (l) =>
        l &&
        !l.startsWith("package") &&
        !l.startsWith("import") &&
        !l.startsWith("//") &&
        !l.startsWith("<script") &&
        !l.startsWith("---"),
    );
  return firstLine ? oneLine(firstLine, 140) : "";
}

function readCodeEntry(abs: string): CodeEntry {
  const raw = fs.readFileSync(abs, "utf-8");
  return {
    relPath: path.relative(ROOT, abs),
    description: extractFirstDocComment(raw),
  };
}

/** Группировка файлов по первому сегменту пути внутри директории. */
function groupByDir(entries: { relPath: string }[], stripPrefix: string): Map<string, number> {
  const groups = new Map<string, number>();
  for (const e of entries) {
    const rel = e.relPath.startsWith(stripPrefix) ? e.relPath.slice(stripPrefix.length) : e.relPath;
    const firstSeg = rel.split("/")[0] || "";
    groups.set(firstSeg, (groups.get(firstSeg) || 0) + 1);
  }
  return groups;
}

function renderMdSection(title: string, entries: MdEntry[]): string {
  const lines: string[] = [`### ${title} (${entries.length})`, ""];
  for (const e of entries) {
    const metaBits: string[] = [];
    if (e.status) metaBits.push(e.status);
    if (e.date) metaBits.push(e.date);
    const meta = metaBits.length ? ` *(${metaBits.join(", ")})*` : "";
    const desc = e.description ? ` — ${e.description}` : "";
    lines.push(`- \`${e.relPath}\` — **${e.title}**${meta}${desc}`);
  }
  lines.push("");
  return lines.join("\n");
}

function renderCodeSection(title: string, entries: CodeEntry[]): string {
  const lines: string[] = [`### ${title} (${entries.length})`, ""];
  for (const e of entries) {
    const desc = e.description ? ` — ${e.description}` : "";
    lines.push(`- \`${e.relPath}\`${desc}`);
  }
  lines.push("");
  return lines.join("\n");
}

function gitLog(limit: number): string[] {
  try {
    const out = execSync(`git log --oneline -n ${limit}`, { cwd: ROOT, encoding: "utf-8" });
    return out.split("\n").filter(Boolean);
  } catch {
    return [];
  }
}

// ─── Main ─────────────────────────────────────────────────────────────────────

const docFiles = walk(path.join(ROOT, "docs"), [".md"]);
const docEntries = docFiles
  .filter((f) => path.basename(f) !== "README.md" && path.basename(f) !== "how-to.md")
  .map(readMdEntry);

const byTopDir = new Map<string, MdEntry[]>();
for (const e of docEntries) {
  const seg = e.relPath.split("/")[1] || "root";
  if (!byTopDir.has(seg)) byTopDir.set(seg, []);
  byTopDir.get(seg)!.push(e);
}

const serverKt = walk(path.join(ROOT, "server", "src"), [".kt"]).map(readCodeEntry);
const appFiles = walk(path.join(ROOT, "app", "src"), [".svelte", ".ts", ".astro"]).map(readCodeEntry);
const scriptFiles = walk(path.join(ROOT, "scripts"), [".ts", ".sh"]).map(readCodeEntry);
const skillFiles = walk(path.join(ROOT, "server", "skills"), [".md"]).map(readMdEntry);

// ─── Render ───────────────────────────────────────────────────────────────────

const out: string[] = [];
out.push("# Карта корпуса проекта");
out.push("");
out.push(`- Сгенерирована: ${new Date().toISOString()}`);
out.push(`- Источник: \`scripts/generate-corpus-map.ts\``);
out.push(`- Формат: путь к файлу → заголовок/метаданные → короткое описание`);
out.push("");
out.push("Эта карта — индекс корпуса для агента. Каждый файл даёт тебе точный путь, по которому ты можешь получить полное содержимое через `read_file`. Детали не выжимаются — они **индексируются**.");
out.push("");
out.push("---");
out.push("");

out.push("## docs/ — контентный слой");
out.push("");

const order = ["profile", "experience", "projects", "skills", "adr", "blog", "philosophy"];
const rendered = new Set<string>();

for (const seg of order) {
  const entries = byTopDir.get(seg);
  if (!entries) continue;
  entries.sort((a, b) => {
    if (a.date && b.date) return a.date < b.date ? 1 : -1;
    return a.relPath.localeCompare(b.relPath);
  });
  out.push(renderMdSection(`docs/${seg}/`, entries));
  rendered.add(seg);
}
for (const [seg, entries] of byTopDir) {
  if (rendered.has(seg)) continue;
  entries.sort((a, b) => a.relPath.localeCompare(b.relPath));
  out.push(renderMdSection(`docs/${seg}/`, entries));
}

if (skillFiles.length) {
  out.push("## server/skills/ — декларативные инструкции агента");
  out.push("");
  skillFiles.sort((a, b) => a.relPath.localeCompare(b.relPath));
  out.push(renderMdSection("server/skills/", skillFiles));
}

if (serverKt.length) {
  out.push("## server/src — Kotlin backend");
  out.push("");
  // Группируем по второй директории внутри commonMain/kotlin/dev/knyazev
  const groups = new Map<string, CodeEntry[]>();
  for (const e of serverKt) {
    const m = e.relPath.match(/dev\/knyazev\/([^/]+)/);
    const key = m ? m[1] : "root";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(e);
  }
  const keys = [...groups.keys()].sort();
  for (const key of keys) {
    const entries = groups.get(key)!;
    entries.sort((a, b) => a.relPath.localeCompare(b.relPath));
    out.push(renderCodeSection(`server/.../${key}/`, entries));
  }
}

if (appFiles.length) {
  out.push("## app/src — Svelte 5 + Astro frontend");
  out.push("");
  const groups = new Map<string, CodeEntry[]>();
  for (const e of appFiles) {
    const rel = e.relPath.replace(/^app\/src\//, "");
    const seg = rel.split("/")[0] || "root";
    if (!groups.has(seg)) groups.set(seg, []);
    groups.get(seg)!.push(e);
  }
  const keys = [...groups.keys()].sort();
  for (const key of keys) {
    const entries = groups.get(key)!;
    entries.sort((a, b) => a.relPath.localeCompare(b.relPath));
    out.push(renderCodeSection(`app/src/${key}/`, entries));
  }
}

if (scriptFiles.length) {
  out.push("## scripts/ — инфраструктурные скрипты");
  out.push("");
  out.push(renderCodeSection("scripts/", scriptFiles));
}

const recentCommits = gitLog(20);
if (recentCommits.length) {
  out.push("## Свежая git-история");
  out.push("");
  out.push("```");
  out.push(...recentCommits);
  out.push("```");
  out.push("");
}

fs.writeFileSync(OUT, out.join("\n"), "utf-8");
const tokensApprox = Math.round(out.join("\n").length / 4);
console.log(`[corpus-map] wrote ${OUT}`);
console.log(`[corpus-map] ${out.join("\n").length} chars (~${tokensApprox} tokens)`);
console.log(`[corpus-map] docs=${docEntries.length} server-kt=${serverKt.length} app=${appFiles.length} scripts=${scriptFiles.length} skills=${skillFiles.length}`);
