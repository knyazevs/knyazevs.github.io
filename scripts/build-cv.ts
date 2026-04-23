import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import matter from "gray-matter";
import puppeteer from "puppeteer";

// Node-compatible dirname (replaces Bun-only import.meta.dir)
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const ROOT = path.resolve(__dirname, "..");
const DOCS = path.join(ROOT, "docs");
// Output directly into app/public so Astro serves /cv.pdf on the site
const OUT = path.join(ROOT, "app", "public", "cv.pdf");

// ─── Types ────────────────────────────────────────────────────────────────────

interface Profile {
  name: string;
  title: string;
  birth_year: number;
  location: string;
  photo?: string;
  contacts: {
    site?: string;
    github?: string;
    linkedin?: string;
    telegram?: string;
    email?: string;
    phone?: string;
  };
}

interface Experience {
  company?: string;
  title?: string;
  period: string;
  cv_description?: string;
  cv_skip?: boolean;
}

interface Skill {
  category: string;
  content: string;
}

interface Education {
  degrees: Array<{ level: string; school: string; years: string }>;
  languages: Array<{ name: string; level: string }>;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function readMd(filePath: string) {
  return matter(fs.readFileSync(filePath, "utf-8"));
}

function calcAge(birthYear: number): number {
  return new Date().getFullYear() - birthYear;
}

/** Extract the content body of a `## Heading` section from markdown. */
function extractSection(markdown: string, heading: string): string {
  const re = new RegExp(`## ${heading}\\n([\\s\\S]*?)(?=\\n## |$)`);
  const m = markdown.match(re);
  return m ? m[1].trim() : "";
}

/**
 * Parse top-level bullet items from markdown, handling multi-line items.
 */
function extractBullets(markdown: string): string[] {
  const items: string[] = [];
  let current = "";
  for (const line of markdown.split("\n")) {
    const trimmed = line.trimStart();
    if (trimmed.startsWith("- ")) {
      if (current) items.push(current);
      current = trimmed.slice(2).trim();
    } else if (current && trimmed) {
      current += " " + trimmed;
    } else if (current && !trimmed) {
      items.push(current);
      current = "";
    }
  }
  if (current) items.push(current);
  return items;
}

/** Extract **bold** terms from markdown */
function extractBoldTerms(markdown: string): string[] {
  const terms: string[] = [];
  const re = /\*\*([^*]+)\*\*/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(markdown)) !== null) {
    terms.push(m[1]);
  }
  return terms;
}

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// ─── Data loading ─────────────────────────────────────────────────────────────

function loadProfile(): {
  meta: Profile;
  summary: string;
  strengths: string[];
  lookingFor: string;
} {
  const { data, content } = readMd(path.join(DOCS, "profile/index.md"));
  const summary = extractSection(content, "Кратко");

  const focusSection = extractSection(content, "Профессиональный фокус");
  const strengthsPart = focusSection.split("**Что не моё:**")[0];
  const strengthsRaw = strengthsPart.replace("**Что делаю хорошо:**", "");
  const strengths = extractBullets(strengthsRaw).map((item) => {
    const colonIdx = item.indexOf(":");
    return colonIdx > 0 ? item.substring(0, colonIdx).trim() : item;
  });

  // Load "Что ищу" from the document instead of hardcoding
  const lookingForRaw = extractSection(content, "Что ищу");
  const lookingFor = lookingForRaw
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l && !/^-{3,}$/.test(l))
    .join(" ");

  return { meta: data as Profile, summary, strengths, lookingFor };
}

function loadPhoto(photoPath?: string): string | null {
  if (!photoPath) return null;
  // Web-style /images/foo/bar.png (рантайм-URL, единый для сайта, LLM и CV) —
  // резолвим в app/public/images (после build-static-data) либо в docs/ (исходник).
  const candidates: string[] = [];
  if (photoPath.startsWith("/images/")) {
    const rel = photoPath.slice("/images/".length);
    candidates.push(path.resolve(ROOT, "app", "public", "images", rel));
    candidates.push(path.resolve(DOCS, rel));
  } else if (path.isAbsolute(photoPath)) {
    candidates.push(photoPath);
  } else {
    candidates.push(path.resolve(ROOT, photoPath));
  }
  const fullPath = candidates.find((p) => fs.existsSync(p));
  if (!fullPath) {
    console.warn(`[cv] Photo not found (tried: ${candidates.join(", ")}) — skipping`);
    return null;
  }
  const ext = path.extname(fullPath).slice(1).toLowerCase();
  const mime = ext === "jpg" ? "jpeg" : ext;
  const data = fs.readFileSync(fullPath);
  return `data:image/${mime};base64,${data.toString("base64")}`;
}

function loadEducation(): Education {
  const { content } = readMd(path.join(DOCS, "profile/04-education.md"));

  const DEGREE_HEADINGS = ["Бакалавриат", "Магистратура", "Аспирантура"];
  const degrees = DEGREE_HEADINGS.map((level) => {
    const section = extractSection(content, level);
    const lines = section.split("\n").map((l) => l.trim()).filter(Boolean);
    return {
      level,
      years: lines[0] ?? "",
      school: abbreviateSchool(lines[1] ?? ""),
    };
  }).filter((d) => d.years);

  const langSection = extractSection(content, "Языки");
  const languages: Education["languages"] = [
    { name: "Русский", level: "Родной" },
  ];
  for (const line of langSection.split("\n")) {
    const m = line.match(/^(.+?)\s*—\s*(.+)$/);
    if (m) languages.push({ name: m[1].trim(), level: m[2].trim() });
  }

  return { degrees, languages };
}

function abbreviateSchool(school: string): string {
  const map: Record<string, string> = {
    "Поволжский государственный технологический университет": "ПГТУ",
  };
  return map[school] ?? school;
}

function loadExperience(): Experience[] {
  const dir = path.join(DOCS, "experience");
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith(".md") && f !== "README.md")
    .sort()
    .map((f) => readMd(path.join(dir, f)).data as Experience)
    .filter((e) => e.company && !e.cv_skip);
}

function loadSkills(): Skill[] {
  const dir = path.join(DOCS, "skills");
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith(".md") && f !== "README.md")
    .sort()
    .map((f) => {
      const { data, content } = readMd(path.join(dir, f));
      return {
        category: (data as any).category ?? f.replace(".md", ""),
        content,
      };
    });
}

// ─── Skill tag definitions ────────────────────────────────────────────────────

const SKILL_NAMES: Record<string, string> = {
  architecture: "Архитектура",
  backend: "Стек",
  leadership: "Лидерство",
  tooling: "Инфраструктура · AI",
};

const SKILL_TAGS: Record<string, string[]> = {
  architecture: ["Low-code платформы", "SaaS", "B2B", "ADR / C4", "Greenfield & Legacy", "Миграции без даунтайма"],
  backend: ["Kotlin / KMP", "Java", "TypeScript / Node.js", "PostgreSQL", "Redis", "Ktor · Exposed"],
  leadership: ["Tech Lead", "Backend Lead", "R&D", "Code Review", "Менторинг", "Преподавание"],
  tooling: ["GitHub Actions", "Jenkins", "Claude Code", "OpenRouter", "Docker / VPS"],
};

// ─── HTML rendering ───────────────────────────────────────────────────────────

const ICONS: Record<string, string> = {
  phone: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.37 1.9.72 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.35 1.85.59 2.81.72A2 2 0 0 1 22 16.92z"/></svg>`,
  email: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"/><polyline points="22,6 12,13 2,6"/></svg>`,
  site: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>`,
  telegram: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M9.78 18.65l.28-4.23 7.68-6.92c.34-.31-.07-.46-.52-.19L7.74 13.3 3.64 12c-.88-.25-.89-.86.2-1.3l15.97-6.16c.73-.33 1.43.18 1.15 1.3l-2.72 12.81c-.19.91-.74 1.13-1.5.71L12.6 16.3l-1.99 1.93c-.23.23-.42.42-.83.42z"/></svg>`,
  linkedin: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M20.45 20.45h-3.55v-5.57c0-1.33-.02-3.04-1.85-3.04-1.86 0-2.14 1.45-2.14 2.95v5.66H9.36V9h3.41v1.56h.05c.48-.9 1.64-1.85 3.37-1.85 3.6 0 4.27 2.37 4.27 5.46v6.28zM5.34 7.43a2.06 2.06 0 1 1 0-4.13 2.06 2.06 0 0 1 0 4.13zm1.78 13.02H3.56V9h3.56v11.45zM22.22 0H1.77C.79 0 0 .77 0 1.72v20.56C0 23.23.79 24 1.77 24h20.45c.98 0 1.78-.77 1.78-1.72V1.72C24 .77 23.2 0 22.22 0z"/></svg>`,
  github: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 .3a12 12 0 0 0-3.79 23.4c.6.1.82-.26.82-.57v-2.17c-3.34.72-4.04-1.61-4.04-1.61-.55-1.39-1.33-1.76-1.33-1.76-1.09-.74.08-.73.08-.73 1.2.09 1.84 1.24 1.84 1.24 1.07 1.83 2.81 1.3 3.49.99.1-.78.42-1.3.76-1.6-2.67-.3-5.47-1.33-5.47-5.93 0-1.31.47-2.38 1.24-3.22-.14-.3-.54-1.52.1-3.18 0 0 1.01-.32 3.31 1.23a11.5 11.5 0 0 1 6.02 0c2.3-1.55 3.3-1.23 3.3-1.23.66 1.66.26 2.88.13 3.18a4.65 4.65 0 0 1 1.23 3.22c0 4.61-2.8 5.63-5.48 5.92.43.37.81 1.1.81 2.22v3.29c0 .32.22.69.83.57A12 12 0 0 0 12 .3"/></svg>`,
};

function renderContacts(contacts: Profile["contacts"]): string {
  const items: string[] = [];
  if (contacts.phone)
    items.push(`${ICONS.phone}<span>${esc(contacts.phone)}</span>`);
  if (contacts.email)
    items.push(`${ICONS.email}<a href="mailto:${contacts.email}">${esc(contacts.email)}</a>`);
  if (contacts.site) {
    const clean = contacts.site.replace(/^https?:\/\//, "");
    items.push(`${ICONS.site}<a href="https://${clean}">${esc(clean)}</a>`);
  }
  if (contacts.telegram)
    items.push(`${ICONS.telegram}<a href="https://t.me/${contacts.telegram.replace("@", "")}">${esc(contacts.telegram)}</a>`);
  if (contacts.linkedin)
    items.push(`${ICONS.linkedin}<a href="https://linkedin.com/in/${contacts.linkedin}">linkedin.com/in/${esc(contacts.linkedin)}</a>`);
  if (contacts.github)
    items.push(`${ICONS.github}<a href="https://github.com/${contacts.github}">github.com/${esc(contacts.github)}</a>`);
  return items.map((i) => `<span class="ci">${i}</span>`).join("");
}

function renderExperience(experiences: Experience[]): string {
  return experiences
    .map((e) => {
      const isCurrent = /н\.в\.|present/i.test(e.period);
      return `
      <div class="exp${isCurrent ? " exp-current" : ""}">
        <div class="exp-row">
          <span class="exp-co">${esc(e.company!)}</span>
          <div class="exp-right">
            ${isCurrent ? '<span class="exp-now">сейчас</span>' : ""}
            <span class="exp-period">${esc(e.period)}</span>
          </div>
        </div>
        ${e.title ? `<div class="exp-role">${esc(e.title)}</div>` : ""}
        ${e.cv_description ? `<p class="exp-desc">${esc(e.cv_description)}</p>` : ""}
      </div>`;
    })
    .join("");
}

function renderSkills(skills: Skill[]): string {
  return skills
    .map((s) => {
      const name = SKILL_NAMES[s.category] ?? s.category;
      const tags = SKILL_TAGS[s.category] ?? extractBoldTerms(s.content).slice(0, 7);
      return `
        <div class="skill-cat">
          <div class="skill-cat-name">${name}</div>
          <div class="skill-tags">
            ${tags.map((t) => `<span class="st">${esc(t)}</span>`).join("")}
          </div>
        </div>`;
    })
    .join("");
}

function renderEducation(edu: Education): string {
  return edu.degrees
    .map(
      (d) => `
      <div class="edu-entry">
        <span class="edu-deg">${d.level}</span>
        <span class="edu-school">${d.school} · ${d.years}</span>
      </div>`,
    )
    .join("");
}

function renderLanguages(edu: Education): string {
  return edu.languages
    .map(
      (l) => `<div class="lang"><span>${l.name}</span><span class="lang-lvl">${l.level}</span></div>`,
    )
    .join("");
}

// ─── Full HTML ────────────────────────────────────────────────────────────────

function buildHtml(
  profile: Profile,
  photoUri: string | null,
  summary: string,
  strengths: string[],
  lookingFor: string,
  education: Education,
  experiences: Experience[],
  skills: Skill[],
): string {
  const age = calcAge(profile.birth_year);
  const yearsInIT = new Date().getFullYear() - 2017;

  const photoHtml = photoUri
    ? `<img class="photo" src="${photoUri}" alt="">`
    : `<div class="photo-placeholder"></div>`;

  const summaryParas = summary
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => `<p>${esc(l)}</p>`)
    .join("");

  const strengthsHtml = strengths.length
    ? `<ul class="str-list">${strengths.map((s) => `<li>${esc(s)}</li>`).join("")}</ul>`
    : "";

  const chips = [
    `${yearsInIT}+ лет разработки`,
    "Kotlin · Java · PostgreSQL",
    "Tech Lead · 7–13 чел.",
    "Low-code · SaaS · Fintech",
  ]
    .map((c) => `<span class="hd-chip">${c}</span>`)
    .join("");

  // Render "Что ищу" from data — single sentence per paragraph
  const lookingForHtml = lookingFor
    .split(/\.\s+/)
    .filter(Boolean)
    .map((s) => `<p>${esc(s.endsWith(".") ? s : s + ".")}</p>`)
    .join("");

  return /* html */ `<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<style>
  /* ── Reset ── */
  * { box-sizing: border-box; margin: 0; padding: 0; }
  a { color: inherit; text-decoration: none; }

  /* ── Tokens (slate + gold accents) ── */
  :root {
    --bg:         #FFFFFF;
    --surface:    #F8FAFC;   /* Серый 50 */
    --surface-2:  #F1F5F9;   /* Серый 100 */
    --border:     #E2E8F0;   /* Серый 200 */
    --text:       #334155;   /* Серый 700 */
    --heading:    #0F172A;   /* Тёмно-синий */
    --sub:        #334155;   /* Серый-синий */
    --muted:      #94A3B8;   /* Серый 400 */
    --dim:        #94A3B8;
    --accent:     #D49934;   /* Золотой */
    --accent-bg:  rgba(212, 153, 52, 0.10);
    --accent-bd:  rgba(212, 153, 52, 0.35);
    --link:       #3B82F6;   /* Синий */
    --warn:       #F97316;   /* Оранжевый */
    --ok:         #22C55E;   /* Зелёный */
  }

  /* ── Page ── */
  body {
    font-family: 'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif;
    font-size: 10pt;
    line-height: 1.5;
    color: var(--text);
    background: var(--bg);
  }

  /* ─────────────────────────── HEADER (slate gradient on light body) ─── */
  .hd {
    background: linear-gradient(135deg, #0F172A, #1E293B);
    color: #FFFFFF;
    padding: 18px 20px 16px;
    display: flex;
    align-items: center;
    gap: 18px;
  }

  .photo {
    width: 112px; height: 112px;
    border-radius: 6px;
    object-fit: cover;
    flex-shrink: 0;
  }
  .photo-placeholder {
    width: 112px; height: 112px;
    border-radius: 6px;
    background: rgba(255,255,255,0.07);
    flex-shrink: 0;
  }

  .hd-info { flex: 1; min-width: 0; }
  .hd-info h1 {
    font-size: 21pt; font-weight: 700;
    letter-spacing: -0.4px; line-height: 1.1;
    margin-bottom: 2px;
    color: #FFFFFF;
  }
  .hd-info .job-title {
    font-size: 10.5pt;
    color: #D49934;
    margin-bottom: 7px;
  }
  .hd-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 8px; }
  .hd-chip {
    background: transparent;
    border: 1px solid rgba(212, 153, 52, 0.55);
    color: #FFFFFF;
    font-size: 7.5pt; font-weight: 600;
    padding: 2px 8px; border-radius: 4px;
    white-space: nowrap;
  }
  .contacts { display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 8.5pt; }
  .ci { color: #CBD5E1; display: inline-flex; align-items: center; gap: 4px; }
  .ci a { color: #CBD5E1; }
  .ci svg {
    width: 10px; height: 10px;
    color: #D49934;
    flex-shrink: 0;
  }

  .hd-meta {
    text-align: right;
    font-size: 9pt; color: #94A3B8;
    white-space: nowrap; line-height: 1.7;
    align-self: flex-start;
    padding-top: 2px;
  }

  /* ─────────────────────────── BODY LAYOUT ─── */
  .body { display: flex; }

  .main { flex: 1; min-width: 0; padding: 12px 18px 12px 18px; }

  .sidebar {
    width: 188px; flex-shrink: 0;
    padding: 12px 14px;
    background: var(--surface);
    border-left: 1px solid var(--border);
  }

  /* ─────────────────────────── SECTIONS ─── */
  .section { margin-bottom: 10px; }
  .section:last-child { margin-bottom: 0; }

  .sec-title {
    font-size: 7.5pt; font-weight: 700;
    text-transform: uppercase; letter-spacing: 1.5px;
    color: var(--accent);
    border-bottom: 1.5px solid var(--accent);
    padding-bottom: 3px; margin-bottom: 6px;
  }

  .sidebar .sec-title {
    font-size: 7pt;
    border-bottom-color: var(--accent-bd);
  }

  /* ─── Summary ─── */
  .sum-text { color: var(--text); font-size: 10pt; line-height: 1.55; }
  .sum-text p + p { margin-top: 3px; }

  .str-list {
    margin: 6px 0 0 14px;
    font-size: 9.5pt; color: var(--sub); line-height: 1.5;
  }
  .str-list li { margin-bottom: 1px; }

  /* ─── Experience ─── */
  .exp {
    margin-bottom: 0; padding-bottom: 12px;
    border-bottom: 1px solid var(--border);
    padding-left: 11px; position: relative;
  }
  .exp:last-of-type { border-bottom: none; padding-bottom: 0; }
  .exp::before {
    content: '';
    position: absolute; left: 0; top: 6px;
    width: 5px; height: 5px;
    border-radius: 50%; background: var(--muted);
    z-index: 1;
  }
  .exp::after {
    content: '';
    position: absolute;
    left: 2px; top: 0; bottom: 0;
    width: 1px; background: var(--border);
    z-index: 0;
  }
  .exp:first-of-type::after { top: 8.5px; }
  .exp:last-of-type::after { bottom: calc(100% - 8.5px); }
  .exp:only-of-type::after { display: none; }

  .exp-current::before {
    background: var(--accent) !important;
    box-shadow: 0 0 0 3px var(--accent-bg);
  }
  .exp-row {
    display: flex; justify-content: space-between;
    align-items: baseline; gap: 8px;
  }
  .exp-co { font-weight: 700; font-size: 10.5pt; color: var(--heading); flex: 1; min-width: 0; }
  .exp-current .exp-co { color: var(--accent); }
  .exp-role { color: var(--muted); font-size: 9.5pt; margin-top: 1px; }
  .exp-right { display: flex; align-items: baseline; gap: 5px; flex-shrink: 0; }
  .exp-now {
    background: var(--warn); color: var(--bg);
    font-size: 6.8pt; font-weight: 700;
    padding: 1px 6px; border-radius: 3px;
    text-transform: uppercase; letter-spacing: 0.5px;
    line-height: 1.4;
  }
  .exp-period {
    color: var(--muted); font-size: 8.5pt; white-space: nowrap; flex-shrink: 0;
    font-weight: 500;
  }
  .exp-desc { color: var(--sub); font-size: 9.5pt; line-height: 1.38; margin-top: 2px; }

  /* ─── Sidebar: Skills ─── */
  .skill-cat { margin-bottom: 7px; }
  .skill-cat-name { font-size: 8.5pt; font-weight: 700; color: var(--heading); margin-bottom: 3px; }
  .skill-tags { display: flex; flex-wrap: wrap; gap: 3px; }
  .st {
    font-size: 7.5pt; color: var(--text);
    background: var(--bg); border: 1px solid var(--border);
    padding: 1px 5px; border-radius: 3px; line-height: 1.45;
  }

  /* ─── Sidebar: Education ─── */
  .edu-entry { margin-bottom: 5px; }
  .edu-deg { font-size: 8.5pt; font-weight: 700; display: block; }
  .edu-school { font-size: 8pt; color: var(--muted); display: block; }

  /* ─── Sidebar: Languages ─── */
  .lang { display: flex; justify-content: space-between; font-size: 8.5pt; margin-bottom: 2px; }
  .lang-lvl { color: var(--muted); }

  /* ─── Sidebar: Looking for ─── */
  .looking {
    font-size: 8pt; color: var(--sub);
    line-height: 1.5; font-style: italic;
  }
  .looking p + p { margin-top: 3px; }
</style>
</head>
<body>

  <!-- ══════ HEADER ══════ -->
  <div class="hd">
    ${photoHtml}
    <div class="hd-info">
      <h1>${esc(profile.name)}</h1>
      <div class="job-title">${esc(profile.title)}</div>
      <div class="hd-chips">${chips}</div>
      <div class="contacts">${renderContacts(profile.contacts)}</div>
    </div>
    <div class="hd-meta">
      ${age} лет<br>${esc(profile.location)}
    </div>
  </div>

  <!-- ══════ BODY ══════ -->
  <div class="body">

    <!-- ── MAIN ── -->
    <div class="main">

      <div class="section">
        <div class="sec-title">О себе</div>
        <div class="sum-text">${summaryParas}</div>
        ${strengthsHtml}
      </div>

      <div class="section">
        <div class="sec-title">Опыт работы</div>
        ${renderExperience(experiences)}
      </div>

    </div>

    <!-- ── SIDEBAR ── -->
    <div class="sidebar">

      <div class="section">
        <div class="sec-title">Компетенции</div>
        ${renderSkills(skills)}
      </div>

      <div class="section">
        <div class="sec-title">Образование</div>
        ${renderEducation(education)}
      </div>

      <div class="section">
        <div class="sec-title">Языки</div>
        ${renderLanguages(education)}
      </div>

      <div class="section">
        <div class="sec-title">Ищу</div>
        <div class="looking">${lookingForHtml}</div>
      </div>

    </div>

  </div>

</body>
</html>`;
}

// ─── Main ─────────────────────────────────────────────────────────────────────

async function main() {
  console.log("[cv] Loading data...");

  const { meta: profile, summary, strengths, lookingFor } = loadProfile();
  const photoUri = loadPhoto(profile.photo);
  const education = loadEducation();
  const experiences = loadExperience();
  const skills = loadSkills();

  console.log(`[cv] ${experiences.length} experience entries, ${skills.length} skill categories`);

  const html = buildHtml(profile, photoUri, summary, strengths, lookingFor, education, experiences, skills);

  // Ensure output directory exists
  fs.mkdirSync(path.dirname(OUT), { recursive: true });

  console.log("[cv] Launching Chromium...");
  const browser = await puppeteer.launch({
    args: ["--no-sandbox", "--disable-setuid-sandbox"],
  });

  try {
    const page = await browser.newPage();
    await page.setContent(html, { waitUntil: "networkidle0" });
    await page.pdf({
      path: OUT,
      format: "A4",
      printBackground: true,
      margin: { top: "0", right: "0", bottom: "0", left: "0" },
    });
  } finally {
    await browser.close();
  }

  console.log(`[cv] Generated → ${path.relative(ROOT, OUT)}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
