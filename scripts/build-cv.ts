import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import matter from "gray-matter";
import {
  buildHtml,
  generatePdf,
  type CvData,
  type Education,
  type Experience,
  type Labels,
  type Profile,
  type Skill,
} from "./cv-lib.ts";

// Node-compatible dirname (replaces Bun-only import.meta.dir)
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const ROOT = path.resolve(__dirname, "..");
const DOCS = path.join(ROOT, "docs");
// Output directly into app/public so Astro serves it on the site.
// Human-readable filename so recruiters save the CV under a clear name.
const OUT = path.join(ROOT, "app", "public", "CV_Sergey_Knyazev_Lead_JVM_Engineer.pdf");

// ─── Helpers ──────────────────────────────────────────────────────────────────

function readMd(filePath: string) {
  return matter(fs.readFileSync(filePath, "utf-8"));
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

// ─── Russian labels & skill tags ────────────────────────────────────────────────

const LABELS: Labels = {
  htmlLang: "ru",
  sections: {
    about: "О себе",
    experience: "Опыт работы",
    skills: "Компетенции",
    education: "Образование",
    languages: "Языки",
    looking: "Ищу",
  },
  nowBadge: "сейчас",
  chips: (yearsInIT) => [
    `${yearsInIT}+ лет разработки`,
    "Kotlin · Java · PostgreSQL",
    "Tech Lead · 7–13 чел.",
    "B2B SaaS · Platform · Fintech",
  ],
};

const SKILL_NAMES: Record<string, string> = {
  architecture: "Архитектура · System Design",
  backend: "Стек · JVM",
  extra: "Дополнительно",
  leadership: "Лидерство · процессы",
  tooling: "Инфраструктура · DevOps",
};

const SKILL_TAGS: Record<string, string[]> = {
  architecture: ["Распределённые системы", "Микросервисы", "B2B SaaS", "API-дизайн", "ADR / C4", "Миграции без даунтайма", "Greenfield & Legacy"],
  backend: ["Kotlin / KMP", "Java", "Spring Boot", "Ktor · Exposed", "PostgreSQL", "Redis", "Koin"],
  extra: ["Micronaut", "jOOQ", "TypeScript / Node.js"],
  leadership: ["Tech Lead", "Backend Lead", "R&D", "Code Review", "Менторинг", "Преподавание", "AI в разработке"],
  tooling: ["Kubernetes", "Docker", "GitHub Actions", "GitLab CI", "TeamCity", "Jenkins", "Claude Code"],
};

// ─── Main ─────────────────────────────────────────────────────────────────────

async function main() {
  console.log("[cv] Loading data...");

  const { meta: profile, summary, strengths, lookingFor } = loadProfile();
  const education = loadEducation();
  const experiences = loadExperience();
  const skills = loadSkills();

  console.log(`[cv] ${experiences.length} experience entries, ${skills.length} skill categories`);

  const data: CvData = { profile, summary, strengths, lookingFor, education, experiences, skills };
  const html = buildHtml(data, LABELS, SKILL_NAMES, SKILL_TAGS);

  await generatePdf(html, OUT);

  console.log(`[cv] Generated → ${path.relative(ROOT, OUT)}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
