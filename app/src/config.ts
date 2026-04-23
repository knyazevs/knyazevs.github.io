export const GITHUB = {
  owner: "knyazevs",
  repo: "knyazevs",
  branch: "main",
};

export const BACKEND_URL =
  (import.meta as { env?: { PUBLIC_BACKEND_URL?: string } }).env?.PUBLIC_BACKEND_URL
  ?? "http://localhost:8080";

// Файлы, которые исключаются из навигации
export const EXCLUDE_FILES = ["how-to.md"];

// Человекочитаемые названия для директорий
export const DIR_LABELS: Record<string, string> = {
  adr: "Архитектурные решения",
  blog: "Блог",
  diagrams: "Диаграммы",
  profile: "Профиль",
  experience: "Опыт",
  projects: "Проекты",
  skills: "Компетенции",
};

// Человекочитаемые названия для конкретных файлов (без расширения)
export const FILE_LABELS: Record<string, string> = {
  "index": "Обо мне",
  "01-principles": "Принципы",
  "02-lessons": "Уроки и провалы",
  "03-industry-views": "Взгляды на индустрию",
  "04-education": "Образование",
};

// Кодовый слой — директории для индексации и отображения
export const CODE_SOURCE_DIRS = ["server/src", "app/src", "scripts"];

export const CODE_EXCLUDED_DIRS = [
  "node_modules", ".git", "build", "dist", ".gradle",
  "out", "generated", ".idea", "cache",
];

export const CODE_INCLUDED_EXTENSIONS = [
  "kt", "kts", "ts", "mjs", "js", "svelte",
  "toml", "yml", "yaml", "json", "md", "conf",
];
