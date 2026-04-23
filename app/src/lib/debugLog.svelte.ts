const MAX_ENTRIES = 60;

export type DebugLogKind = "req" | "res" | "evt" | "err";

export interface DebugLogEntry {
    id: number;
    kind: DebugLogKind;
    text: string;
    ts: number;
}

let nextId = 0;

export const debugLog = $state<{ entries: DebugLogEntry[] }>({ entries: [] });

export function pushLog(kind: DebugLogKind, text: string): void {
    const entry: DebugLogEntry = {
        id: ++nextId,
        kind,
        text,
        ts: performance.now(),
    };
    if (debugLog.entries.length >= MAX_ENTRIES) {
        debugLog.entries = [...debugLog.entries.slice(-(MAX_ENTRIES - 1)), entry];
    } else {
        debugLog.entries = [...debugLog.entries, entry];
    }
}

export function clearLog(): void {
    debugLog.entries = [];
}
