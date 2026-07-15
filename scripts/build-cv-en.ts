// English CV — point-in-time snapshot translated from the Russian source in docs/.
// Unlike build-cv.ts, this does NOT read docs/: the content lives inline here so
// the docs corpus stays purely Russian and there is no dual-source machinery.
// If the Russian descriptions change materially, update this snapshot by hand.
//
//   node --experimental-strip-types scripts/build-cv-en.ts   → app/public/CV_Sergey_Knyazev_Lead_JVM_Engineer_EN.pdf

import path from "path";
import { fileURLToPath } from "url";
import {
  buildHtml,
  generatePdf,
  type CvData,
  type Labels,
} from "./cv-lib.ts";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const OUT = path.join(ROOT, "app", "public", "CV_Sergey_Knyazev_Lead_JVM_Engineer_EN.pdf");

const SCHOOL = "Volga State Univ. of Technology";

// ─── English content snapshot ───────────────────────────────────────────────────

const DATA: CvData = {
  profile: {
    name: "Sergey Knyazev",
    title: "Technical Lead / Architect · JVM (Kotlin, Java)",
    birth_year: 1996,
    location: "Remote",
    contacts: {
      site: "sknyazev.pro",
      github: "knyazevs",
      linkedin: "s-knyazev",
      telegram: "@sknyazev",
      email: "s_knyazev@vk.com",
    },
  },

  summary: [
    "Technical Lead / Architect with 9+ years of commercial software development. 7 years as a technical leader — designing distributed backend systems and B2B SaaS platforms, leading teams of 7–13 people.",
    "Core stack: JVM (Kotlin, Java), Spring Boot, Ktor, Exposed, PostgreSQL. AI integrated into the workflow.",
    "Focus: architecture, R&D, zero-downtime migrations, scaling products from MVP.",
    "Author of [Kormium](https://github.com/kormium/kormium) — an open-source ORM for Kotlin Multiplatform (Maven Central, Apache 2.0): public API design, PostgreSQL on Kotlin/Native via libpq. Deep understanding of database internals, transactions and concurrency.",
  ].join("\n"),

  strengths: [],

  lookingFor:
    "Technical Lead · Architect · Principal Engineer. Backend or platform teams. Remote or hybrid.",

  education: {
    degrees: [
      { level: "Bachelor's", school: SCHOOL, years: "2014–2018" },
      { level: "Master's", school: SCHOOL, years: "2018–2020" },
      { level: "PhD studies", school: SCHOOL, years: "2020–2021 (partial)" },
    ],
    languages: [
      { name: "Russian", level: "Native" },
      { name: "English", level: "B2" },
    ],
  },

  experiences: [
    {
      company: "MRS · B2B SaaS platform",
      title: "Tech Lead / Architect",
      period: "2022–present",
      cv_description:
        "Key technical decision-maker of a B2B SaaS platform: 10+ services, 4–6 products, 30–60 enterprise clients (incl. Polyus, Mosinzhproekt). Team of 7–13. Introduced Kotlin Multiplatform: the entire business layer and multi-tenant infrastructure were moved into shared JVM/JS libraries — client apps handle only the UI. New services on Ktor + Exposed: 3–5 s startup instead of 25–30 s and ~130 MB memory instead of ~225 MB with Spring Boot + Hibernate. Led the R&D track (KMP, QUIC/HTTP-3, AI tooling).",
    },
    {
      company: "MRS · Plotpad",
      title: "Tech Lead",
      period: "2022–2023",
      cv_description:
        "Technical lead of a B2B construction-control product. Initiated and delivered a MongoDB → PostgreSQL migration together with a move from bare metal to a managed database: removed MongoDB maintenance (upgrades broke backward compatibility and forced code changes), freed up DevOps time, improved reliability. Maintained and developed the product until it was moved into sunset.",
    },
    {
      company: "Volga State Univ. · Software Systems Dept.",
      title: "Lecturer",
      period: "2022–2025",
      cv_description:
        "Part-time. Modern development methods, software design and architecture for 3rd–4th-year students — 100–120 students per year. Bringing hands-on industry experience into academia.",
    },
    {
      company: "GXB Ventures",
      title: "Tech Lead / Backend Lead",
      period: "2019–2022",
      cv_description:
        "Built and led the backend team (7–13 people) at an international technology incubator (Los Angeles, Montreal, Yoshkar-Ola). A B2B SaaS collaboration platform: video, chat, documents, interactive whiteboard. Scaled an architecture that had grown out of an MVP. Researched and early-adopted the QUIC protocol.",
    },
    {
      company: "Omega-R",
      title: "Software Engineer",
      period: "2019",
      cv_description:
        "Backend of a news service for a state media agency (NestJS, Node.js, TypeScript). Short-term contract at the project's initial phase: application design and scaffolding.",
    },
    {
      company: "ACI Worldwide",
      title: "Junior Software Engineer",
      period: "2017–2019",
      cv_description:
        "Refactoring and development of the international PayON payment platform, a connector to a banking system, and log-analysis tooling. Enterprise Java, fintech integrations.",
    },
  ],

  // category keys must match SKILL_NAMES / SKILL_TAGS below; content is unused
  // because EN tags are supplied explicitly.
  skills: [
    { category: "architecture", content: "" },
    { category: "backend", content: "" },
    { category: "extra", content: "" },
    { category: "leadership", content: "" },
    { category: "tooling", content: "" },
  ],
};

// ─── English labels & skill tags ─────────────────────────────────────────────────

const LABELS: Labels = {
  htmlLang: "en",
  sections: {
    about: "Summary",
    experience: "Experience",
    skills: "Skills",
    education: "Education",
    languages: "Languages",
    looking: "Looking for",
  },
  nowBadge: "now",
  chips: (yearsInIT) => [
    `${yearsInIT}+ years in development`,
    "Kotlin · Java · PostgreSQL",
    "Tech Lead · 7–13 people",
    "B2B SaaS · Platform · Fintech",
  ],
};

const SKILL_NAMES: Record<string, string> = {
  architecture: "Architecture · System Design",
  backend: "Stack · JVM",
  extra: "Also",
  leadership: "Leadership · Process",
  tooling: "Infrastructure · DevOps",
};

const SKILL_TAGS: Record<string, string[]> = {
  architecture: ["Distributed systems", "Microservices", "B2B SaaS", "API design", "ADR / C4", "Zero-downtime migrations", "Greenfield & Legacy"],
  backend: ["Kotlin / KMP", "Java", "Spring Boot", "Ktor · Exposed", "PostgreSQL", "Redis", "Koin"],
  extra: ["Micronaut", "jOOQ", "TypeScript / Node.js"],
  leadership: ["Tech Lead", "Backend Lead", "R&D", "Code Review", "Mentoring", "Teaching", "AI in delivery"],
  tooling: ["Kubernetes", "Docker", "GitHub Actions", "GitLab CI", "TeamCity", "Jenkins", "Claude Code"],
};

// ─── Main ─────────────────────────────────────────────────────────────────────

async function main() {
  console.log("[cv-en] Rendering English snapshot...");

  const html = buildHtml(DATA, LABELS, SKILL_NAMES, SKILL_TAGS);
  await generatePdf(html, OUT);

  console.log(`[cv-en] Generated → ${path.relative(ROOT, OUT)}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
