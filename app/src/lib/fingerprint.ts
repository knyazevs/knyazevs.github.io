/**
 * Browser fingerprint computation (ADR-18).
 *
 * Combines canvas rendering (GPU/font-level uniqueness) with browser signals
 * and hashes everything with SHA-256 via the Web Crypto API.
 *
 * Result is cached in-module — computed once per page session.
 */

let cachedFingerprint: string | null = null;

export async function computeFingerprint(): Promise<string> {
  if (cachedFingerprint !== null) return cachedFingerprint;

  const canvas = buildCanvasData();
  const components = [
    navigator.userAgent,
    navigator.language,
    String(navigator.hardwareConcurrency ?? ""),
    String(screen.width),
    String(screen.height),
    String(screen.colorDepth),
    String(window.devicePixelRatio ?? ""),
    Intl.DateTimeFormat().resolvedOptions().timeZone,
    canvas,
  ].join("|||");

  cachedFingerprint = await sha256(components);
  return cachedFingerprint;
}

// ─── Internals ────────────────────────────────────────────────────────────────

function buildCanvasData(): string {
  try {
    const canvas = document.createElement("canvas");
    canvas.width = 280;
    canvas.height = 60;
    const ctx = canvas.getContext("2d");
    if (!ctx) return "no-canvas";

    // Background fill — colour rendering varies by compositing pipeline
    ctx.fillStyle = "rgb(255, 102, 0)";
    ctx.fillRect(10, 10, 100, 30);

    // Text rendering — varies by GPU, font hinting, subpixel rendering
    ctx.fillStyle = "rgb(0, 100, 200)";
    ctx.font = "bold 14px Arial, sans-serif";
    ctx.textBaseline = "alphabetic";
    ctx.fillText("fp\u2603\uD83D\uDD11", 20, 32); // snowman + key emoji stress-test renderer

    // Semi-transparent overlay — blending depends on GPU
    ctx.globalAlpha = 0.5;
    ctx.fillStyle = "rgb(0, 200, 100)";
    ctx.fillRect(60, 10, 100, 30);

    return canvas.toDataURL();
  } catch {
    return "canvas-error";
  }
}

async function sha256(input: string): Promise<string> {
  const buffer = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(input)
  );
  return Array.from(new Uint8Array(buffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
