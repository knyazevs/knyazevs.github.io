<script lang="ts">
    import { onMount, tick } from "svelte";
    import { BACKEND_URL } from "../config";
    import { authHeaders } from "../lib/session";
    import { marked } from "marked";
    import CosmicOrb from "./CosmicOrb.svelte";
    import ContentModal, { type ContentTab } from "./ContentModal.svelte";
    import DebugLogStream from "./DebugLogStream.svelte";
    import { pushLog } from "../lib/debugLog.svelte";
    import { imagePreviewState, closeImagePreview } from "../lib/imagePreview.svelte";
    import { type UiBlock } from "./blocks/BlockRegistry";
    import TextBlock from "./blocks/TextBlock.svelte";
    import ImageBlock from "./blocks/ImageBlock.svelte";
    import ImageGalleryBlock from "./blocks/ImageGalleryBlock.svelte";
    import LinkListBlock from "./blocks/LinkListBlock.svelte";
    import TextWithImageBlock from "./blocks/TextWithImageBlock.svelte";

    let { autoOpenTab = null }: { autoOpenTab?: ContentTab | null } = $props();

    type PanelState = "idle" | "listening" | "thinking" | "speaking" | "error";

    const DETAIL_MARKER = "---DETAIL---";

    // ADR-025: Message — массив блоков, один из типов (text) стримится по токенам.
    // Маркер DETAIL_MARKER живёт внутри text-блока, разбиение делает TextBlock.svelte.
    interface Message {
        question: string;
        blocks: UiBlock[];
        sources: string[];
    }

    // ADR-025: миграция старой истории sessionStorage (answer + blocks → [text-блок, ...blocks]).
    function migrateMessage(m: any): Message {
        if (m && typeof m === "object" && typeof m.answer === "string") {
            const legacyText: UiBlock = {
                id: `legacy-${Math.random().toString(36).slice(2, 8)}`,
                type: "text",
                content: m.answer,
            };
            const visual: UiBlock[] = Array.isArray(m.blocks)
                ? m.blocks.filter((b: any) => b && b.type !== "text")
                      .map((b: any, i: number) => ({ id: b.id ?? `legacy-vis-${i}`, ...b }))
                : [];
            return {
                question: m.question ?? "",
                sources: Array.isArray(m.sources) ? m.sources : [],
                blocks: [legacyText, ...visual],
            };
        }
        return m as Message;
    }

    function findStreamingTextBlockIdx(blocks: UiBlock[]): number {
        for (let i = blocks.length - 1; i >= 0; i--) {
            const b = blocks[i];
            if (b.type === "text" && b.streaming) return i;
        }
        return -1;
    }

    let state: PanelState = $state("idle");
    let inputText = $state("");
    let errorMessage = $state("");
    let streamingIdx = $state<number | null>(null);
    let history: Message[] = $state([]);
    let modalOpen = $state(false);
    let modalTab: ContentTab = $state("docs");
    let modalOpenPath = $state<string | null>(null);
    let pendingSources: string[] = [];

    function openModal(tab: ContentTab, path: string | null = null) {
        modalTab = tab;
        modalOpenPath = path;
        modalOpen = true;
    }

    function closeModal() {
        modalOpen = false;
        modalOpenPath = null;
        closeImagePreview();
    }

    // Картинка раскрывается через универсальный ContentModal в image-режиме:
    // блоки зовут openImagePreview() → стор обновляется → здесь включаем modalOpen.
    $effect(() => {
        if (imagePreviewState.value) modalOpen = true;
    });

    let messagesEl: HTMLElement | undefined = $state();
    let inputEl: HTMLTextAreaElement | undefined = $state();

    let mediaRecorder: MediaRecorder | null = null;
    let audioChunks: BlobPart[] = [];
    let currentAudio: HTMLAudioElement | null = null;
    let ttsEnabled = $state(true);

    // ─── Voice reactivity (mic → orb) ───────────────────────────────────────
    let voiceLevel = $state(0);
    let voiceAudioCtx: AudioContext | null = null;
    let voiceAnalyser: AnalyserNode | null = null;
    let voiceSource: MediaStreamAudioSourceNode | null = null;
    let voiceRaf = 0;

    function startVoiceAnalysis(stream: MediaStream) {
        try {
            voiceAudioCtx = new AudioContext();
            voiceSource = voiceAudioCtx.createMediaStreamSource(stream);
            voiceAnalyser = voiceAudioCtx.createAnalyser();
            voiceAnalyser.fftSize = 512;
            voiceAnalyser.smoothingTimeConstant = 0.4;
            voiceSource.connect(voiceAnalyser);
            const buf = new Uint8Array(voiceAnalyser.frequencyBinCount);
            let smoothed = 0;
            const tick = () => {
                if (!voiceAnalyser) return;
                voiceAnalyser.getByteTimeDomainData(buf);
                let sum = 0;
                for (let i = 0; i < buf.length; i++) {
                    const v = (buf[i] - 128) / 128;
                    sum += v * v;
                }
                const rms = Math.sqrt(sum / buf.length);
                // Scale so normal speech ≈ 0.5–0.8, whisper low, loud ≈ 1
                const raw = Math.min(1, rms * 3.2);
                smoothed = smoothed * 0.55 + raw * 0.45;
                voiceLevel = smoothed;
                voiceRaf = requestAnimationFrame(tick);
            };
            tick();
        } catch {
            /* без анализа — не критично */
        }
    }

    function stopVoiceAnalysis() {
        if (voiceRaf) cancelAnimationFrame(voiceRaf);
        voiceRaf = 0;
        try { voiceSource?.disconnect(); } catch {}
        try { voiceAnalyser?.disconnect(); } catch {}
        voiceSource = null;
        voiceAnalyser = null;
        if (voiceAudioCtx) {
            voiceAudioCtx.close().catch(() => {});
            voiceAudioCtx = null;
        }
        voiceLevel = 0;
    }

    // ─── Agent mode (ADR-027) ───────────────────────────────────────────────
    const AGENT_MODE_KEY = "agent-mode";
    type AgentMode = "agentic" | "full_context" | "rag";
    let agentMode = $state<AgentMode>("agentic");

    function setAgentMode(m: AgentMode) {
        agentMode = m;
        try { sessionStorage.setItem(AGENT_MODE_KEY, m); } catch {}
        pushLog("evt", `agent mode = ${m}`);
    }

    // ─── Easter eggs ────────────────────────────────────────────────────────
    const DEBUG_KEY = "debug-mode";
    let debugMode = $state(false);
    let orbClickCount = $state(0);
    let orbPulseNonce = $state(0);
    let promoMessage = $state("");
    let promoTimer: ReturnType<typeof setTimeout> | null = null;

    function triggerOrbClick() {
        orbPulseNonce = performance.now();
        handleOrbClick();
    }

    function disableDebug() {
        debugMode = false;
        debugAcknowledged = false;
        orbClickCount = 0;
        try { sessionStorage.removeItem(DEBUG_KEY); } catch {}
        flashPromo("Debug режим: OFF", 1600);
    }

    const PROMO_STAGES = [
        { at: 1,  msg: "Что-то зашевелилось…" },
        { at: 3,  msg: "Хм, интересно." },
        { at: 5,  msg: "Вы близко." },
        { at: 7,  msg: "Вы почти разработчик." },
        { at: 9,  msg: "Ещё чуть-чуть…" },
        { at: 10, msg: "Поздравляем — вы разработчик.", enableDebug: true },
    ];

    function flashPromo(msg: string, ms = 1800) {
        promoMessage = msg;
        if (promoTimer) clearTimeout(promoTimer);
        promoTimer = setTimeout(() => { promoMessage = ""; }, ms);
    }

    let debugAcknowledged = false;

    function handleOrbClick() {
        if (debugMode) {
            // Один раз сообщаем, что режим уже включён — дальше клики декоративны.
            if (!debugAcknowledged) {
                debugAcknowledged = true;
                flashPromo("Debug режим уже включён", 1600);
            }
            return;
        }
        orbClickCount += 1;
        const stage = PROMO_STAGES.find((s) => s.at === orbClickCount);
        if (!stage) return;
        flashPromo(stage.msg, stage.enableDebug ? 2600 : 1800);
        if (stage.enableDebug) {
            debugMode = true;
            debugAcknowledged = true;
            try { sessionStorage.setItem(DEBUG_KEY, "1"); } catch {}
        }
    }

    // Konami: ↑↑↓↓←→←→BA
    const KONAMI = [
        "ArrowUp","ArrowUp","ArrowDown","ArrowDown",
        "ArrowLeft","ArrowRight","ArrowLeft","ArrowRight",
        "KeyB","KeyA",
    ];
    let konamiIdx = 0;

    function onGlobalKey(e: KeyboardEvent) {
        if (e.code === KONAMI[konamiIdx]) {
            konamiIdx += 1;
            if (konamiIdx === KONAMI.length) {
                konamiIdx = 0;
                debugMode = !debugMode;
                try {
                    if (debugMode) sessionStorage.setItem(DEBUG_KEY, "1");
                    else sessionStorage.removeItem(DEBUG_KEY);
                } catch {}
                flashPromo(
                    debugMode ? "Debug режим: ON" : "Debug режим: OFF",
                    2000,
                );
            }
        } else {
            konamiIdx = e.code === KONAMI[0] ? 1 : 0;
        }
    }

    // ─── View mode ──────────────────────────────────────────────────────────────
    let chatMode = $state(false);

    const MODE_KEY = "chat-mode";

    function openChatMode() {
        chatMode = true;
        sessionStorage.setItem(MODE_KEY, "1");
        // Scroll to bottom after mode switch
        tick().then(scrollMessages);
    }

    function closeChatMode() {
        chatMode = false;
        sessionStorage.setItem(MODE_KEY, "0");
    }

    // ─── Autocomplete ───────────────────────────────────────────────────────────

    let ghostText = $state("");
    let acAbort: AbortController | null = null;
    let acTimer: ReturnType<typeof setTimeout> | null = null;

    function clearAutocomplete() {
        ghostText = "";
        if (acTimer) {
            clearTimeout(acTimer);
            acTimer = null;
        }
        if (acAbort) {
            acAbort.abort();
            acAbort = null;
        }
    }

    function scheduleAutocomplete(input: string) {
        clearAutocomplete();
        const trimmed = input.trim();
        if (trimmed.length < 3) return;

        // 1) Local prefix match against suggestions + followUps
        const allHints = [...suggestions, ...followUps];
        const localMatch = allHints.find(
            (s) =>
                s.toLowerCase().startsWith(trimmed.toLowerCase()) &&
                s.length > trimmed.length,
        );
        if (localMatch) {
            ghostText = localMatch.slice(trimmed.length);
            return;
        }

        // 2) Server autocomplete with debounce
        acTimer = setTimeout(async () => {
            acAbort = new AbortController();
            try {
                pushLog("req", `POST /api/autocomplete len=${trimmed.length}`);
                const res = await fetch(`${BACKEND_URL}/api/autocomplete`, {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json",
                        ...(await authHeaders()),
                    },
                    body: JSON.stringify({ input: trimmed }),
                    signal: acAbort.signal,
                });
                if (!res.ok) { pushLog("err", `autocomplete ${res.status}`); return; }
                const data = await res.json();
                pushLog("res", `autocomplete 200${data.completion ? ` → +${data.completion.length}ch` : " ∅"}`);
                // Only show if input hasn't changed while we waited
                if (inputText.trim() === trimmed && data.completion) {
                    ghostText = data.completion;
                }
            } catch {
                // aborted or network error — ignore
            }
        }, 300);
    }

    function acceptAutocomplete() {
        if (!ghostText) return false;
        inputText = inputText + ghostText;
        ghostText = "";
        return true;
    }

    // ─── Slash commands ─────────────────────────────────────────────────────

    type SlashHandler = () => void | Promise<void>;
    interface SlashCommand {
        name: string;
        description: string;
        handler: SlashHandler;
    }

    const SLASH_COMMANDS: SlashCommand[] = [
        { name: "/cv",         description: "скачать резюме (PDF)",    handler: () => { window.open("/cv.pdf", "_blank"); } },
        { name: "/blog",       description: "записи в блоге",          handler: () => openModal("docs") },
        { name: "/adr",        description: "архитектурные решения",   handler: () => openModal("docs") },
        { name: "/code",       description: "браузер кода",            handler: () => openModal("code") },
        { name: "/timeline",   description: "история коммитов",        handler: () => openModal("timeline") },
        { name: "/skills",     description: "навыки",                  handler: () => openModal("docs") },
        { name: "/experience", description: "опыт работы",             handler: () => openModal("docs") },
        { name: "/profile",    description: "обо мне",                 handler: () => openModal("docs") },
        { name: "/github",     description: "репозиторий на GitHub",   handler: () => { window.open("https://github.com/knyazevs/sknyazev", "_blank"); } },
        { name: "/contact",    description: "контакты",                handler: () => injectReply("/contact", CONTACT_REPLY) },
        { name: "/help",       description: "список всех команд",      handler: () => injectReply("/help", helpReply()) },
        { name: "/clear",      description: "очистить историю чата",   handler: clearChat },
        { name: "/theme",      description: "переключить тему",        handler: () => { toggleTheme(); } },
        { name: "/whoami",     description: "кто ты такой",            handler: () => injectReply("/whoami", WHOAMI_REPLY) },
        { name: "/promote",    description: "серьёзный аргумент",      handler: () => injectReply("/promote", PROMOTE_REPLY) },
        { name: "/sudo",       description: "стать root",              handler: () => injectReply("/sudo", "Permission denied. Nice try. 🤨") },
        { name: "/matrix",     description: "follow the white rabbit", handler: activateMatrix },
    ];

    const CONTACT_REPLY =
        "**Контакты**\n\n" +
        "- email · `s_knyazev@vk.com`\n" +
        "- telegram · `@sknyazev`\n" +
        "- github · [knyazevs](https://github.com/knyazevs)\n" +
        "- linkedin · [s-knyazev](https://linkedin.com/in/s-knyazev)";

    const WHOAMI_REPLY =
        "```\nsergey@knyazevs:~$ whoami\n" +
        "  role       Technical Lead / Architect\n" +
        "  stack      Kotlin · Ktor · PostgreSQL\n" +
        "  experience 9+ years commercial, 7 as lead\n" +
        "  mode       remote · Russia\n" +
        "  uptime     since 1996\n" +
        "```";

    const PROMOTE_REPLY =
        "Если вы здесь дошли до `/promote` — вы уже понимаете, что я не пишу про себя в третьем лице.\n\n" +
        "Что я делаю хорошо: превращаю расплывчатое «надо спроектировать» в работающий код, принимаю решения и записываю их так, чтобы команда могла опереться.\n\n" +
        "Если ищете Technical Lead / Architect — напишите: `s_knyazev@vk.com`.";

    function helpReply(): string {
        const lines = SLASH_COMMANDS
            .filter((c) => c.name !== "/help")
            .map((c) => `- \`${c.name}\` — ${c.description}`);
        return "**Доступные команды**\n\n" + lines.join("\n");
    }

    function injectReply(question: string, answer: string) {
        const textBlock: UiBlock = {
            id: `inj-${Math.random().toString(36).slice(2, 8)}`,
            type: "text",
            content: answer,
        };
        history = [...history, { question, sources: [], blocks: [textBlock] }];
        persistChat();
        if (chatMode) scrollMessages();
    }

    function clearChat() {
        history = [];
        followUps = [];
        streamingIdx = null;
        persistChat();
        if (chatMode) closeChatMode();
    }

    let matrixMode = $state(false);
    let matrixCanvas: HTMLCanvasElement | undefined = $state();
    function activateMatrix() {
        matrixMode = true;
        setTimeout(() => { matrixMode = false; }, 6000);
    }

    $effect(() => {
        if (!matrixMode || !matrixCanvas) return;
        const canvas = matrixCanvas;
        const ctx = canvas.getContext("2d");
        if (!ctx) return;

        const FONT_SIZE = 16;
        const CHARS =
            "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        let drops: number[] = [];

        function resize() {
            const dpr = window.devicePixelRatio || 1;
            canvas.width = window.innerWidth * dpr;
            canvas.height = window.innerHeight * dpr;
            canvas.style.width = `${window.innerWidth}px`;
            canvas.style.height = `${window.innerHeight}px`;
            ctx!.setTransform(dpr, 0, 0, dpr, 0, 0);
            const cols = Math.ceil(window.innerWidth / FONT_SIZE);
            drops = Array.from({ length: cols }, () => Math.random() * -40);
            ctx!.fillStyle = "#000";
            ctx!.fillRect(0, 0, window.innerWidth, window.innerHeight);
        }
        resize();
        window.addEventListener("resize", resize);

        let raf = 0;
        function draw() {
            ctx!.fillStyle = "rgba(0, 0, 0, 0.06)";
            ctx!.fillRect(0, 0, window.innerWidth, window.innerHeight);
            ctx!.font = `${FONT_SIZE}px "JetBrains Mono", monospace`;
            ctx!.textBaseline = "top";

            for (let i = 0; i < drops.length; i++) {
                const ch = CHARS[Math.floor(Math.random() * CHARS.length)];
                const x = i * FONT_SIZE;
                const y = drops[i] * FONT_SIZE;

                ctx!.fillStyle = "#cfffd0";
                ctx!.fillText(ch, x, y);
                ctx!.fillStyle = "#00ff66";
                ctx!.fillText(ch, x, y - FONT_SIZE);

                if (y > window.innerHeight && Math.random() > 0.975) {
                    drops[i] = 0;
                }
                drops[i]++;
            }
            raf = requestAnimationFrame(draw);
        }
        draw();

        return () => {
            cancelAnimationFrame(raf);
            window.removeEventListener("resize", resize);
        };
    });

    // Dropdown state
    let slashOpen = $state(false);
    let slashQuery = $state("");
    let slashIdx = $state(0);
    let slashListEl: HTMLElement | undefined = $state();

    $effect(() => {
        // Keep active slash item visible as user navigates the list.
        if (!slashOpen || !slashListEl) return;
        const active = slashListEl.querySelector<HTMLElement>(
            ".slash-item.active",
        );
        active?.scrollIntoView({ block: "nearest" });
        void slashIdx;
    });

    let slashMatches = $derived.by(() => {
        if (!slashOpen) return [] as SlashCommand[];
        const q = slashQuery.toLowerCase();
        return SLASH_COMMANDS.filter((c) => c.name.toLowerCase().startsWith(q));
    });

    function updateSlashState(value: string) {
        if (value.startsWith("/")) {
            slashOpen = true;
            slashQuery = value.trim();
            slashIdx = 0;
        } else {
            slashOpen = false;
            slashQuery = "";
        }
    }

    async function executeSlash(cmd: SlashCommand) {
        inputText = "";
        slashOpen = false;
        slashQuery = "";
        clearAutocomplete();
        try { await cmd.handler(); }
        catch (e) { console.warn("slash handler failed", e); }
    }

    function tryExecuteSlashFromInput(): boolean {
        const raw = inputText.trim();
        if (!raw.startsWith("/")) return false;
        // Exact match first
        const exact = SLASH_COMMANDS.find((c) => c.name === raw);
        if (exact) { executeSlash(exact); return true; }
        // Otherwise take currently highlighted suggestion
        const pick = slashMatches[slashIdx];
        if (pick) { executeSlash(pick); return true; }
        return false;
    }

    // ─── Suggestions ────────────────────────────────────────────────────────────

    const DEFAULT_SUGGESTIONS = [
        "Какой стек использует Сергей?",
        "Расскажи про RAG в проекте",
        "Какой опыт в архитектуре?",
        "Почему Kotlin/Ktor?",
    ];

    let suggestions: string[] = $state(DEFAULT_SUGGESTIONS);
    let followUps: string[] = $state([]);

    const HISTORY_KEY = "chat-history";
    const FOLLOWUPS_KEY = "chat-followups";

    function persistChat() {
        try {
            sessionStorage.setItem(HISTORY_KEY, JSON.stringify(history));
            sessionStorage.setItem(FOLLOWUPS_KEY, JSON.stringify(followUps));
        } catch {
            /* quota exceeded — ignore */
        }
    }

    onMount(() => {
        try {
            const saved = sessionStorage.getItem(HISTORY_KEY);
            if (saved) {
                const parsed: any[] = JSON.parse(saved);
                if (parsed.length) history = parsed.map(migrateMessage);
            }
            const savedFollowUps = sessionStorage.getItem(FOLLOWUPS_KEY);
            if (savedFollowUps) {
                const parsed: string[] = JSON.parse(savedFollowUps);
                if (parsed.length) followUps = [...parsed];
            }
            chatMode = sessionStorage.getItem(MODE_KEY) === "1";
        } catch {
            /* corrupted — start fresh */
        }

        ttsEnabled = localStorage.getItem("tts") !== "off";

        try { debugMode = sessionStorage.getItem(DEBUG_KEY) === "1"; } catch {}
        try {
            const savedMode = sessionStorage.getItem(AGENT_MODE_KEY);
            if (savedMode === "agentic" || savedMode === "full_context" || savedMode === "rag") {
                agentMode = savedMode;
            }
        } catch {}

        window.addEventListener("keydown", onGlobalKey);

        if (autoOpenTab) openModal(autoOpenTab);

        (async () => {
            try {
                pushLog("req", `GET /api/suggestions`);
                const res = await fetch(`${BACKEND_URL}/api/suggestions`, {
                    headers: await authHeaders(),
                });
                if (res.ok) {
                    const data = await res.json();
                    if (data.suggestions?.length) {
                        suggestions = data.suggestions;
                        pushLog("res", `suggestions 200 n=${data.suggestions.length}`);
                    } else {
                        pushLog("res", `suggestions 200 ∅`);
                    }
                } else {
                    pushLog("err", `suggestions ${res.status}`);
                }
            } catch {
                pushLog("err", `suggestions network`);
            }
        })();

        return () => {
            window.removeEventListener("keydown", onGlobalKey);
        };
    });


    async function scrollMessages() {
        await tick();
        if (messagesEl) {
            messagesEl.scrollTop = messagesEl.scrollHeight;
        }
    }

    // ─── Chat ─────────────────────────────────────────────────────────────────

    async function submitQuery(question: string) {
        if (!question.trim() || state === "thinking") return;
        inputText = "";
        errorMessage = "";
        followUps = [];
        clearAutocomplete();

        const questionText = question.trim();
        history = [
            ...history,
            { question: questionText, sources: [], blocks: [] },
        ];
        const msgIdx = history.length - 1;
        streamingIdx = msgIdx;
        state = "thinking";
        if (chatMode) await scrollMessages();

        try {
            pushLog("req", `POST /api/chat len=${questionText.length}`);
            const response = await fetch(`${BACKEND_URL}/api/chat`, {
                method: "POST",
                headers: { "Content-Type": "application/json", ...(await authHeaders()) },
                body: JSON.stringify({ question: questionText, mode: agentMode }),
            });

            if (!response.ok) {
                pushLog("err", `chat ${response.status}`);
                const body = await response.json().catch(() => null);
                throw new Error(
                    body?.error ?? `Server error: ${response.status}`,
                );
            }
            if (!response.body) {
                throw new Error(`Server error: ${response.status}`);
            }
            pushLog("res", `chat 200 stream open`);

            state = "speaking";
            let tokenCount = 0;
            let tokenBatchStart = 0;
            const TOKEN_BATCH = 15;
            const reader = response.body.getReader();
            const decoder = new TextDecoder();

            // ADR-025: SSE-протокол — block_start/token/block_end/block. Текст приходит
            // как поток токенов, привязанных к id открытого text-блока. TTS работает с
            // первым text-блоком до маркера ---DETAIL--- (как раньше).
            const ttsQueue: Promise<Blob>[] = [];
            let ttsSentBuffer = "";
            let ttsFullText = "";
            let ttsTrackedBlockId: string | null = null;
            let detailStarted = false;
            const blocksById = new Map<string, UiBlock>();

            const tryAppendTts = () => {
                if (!ttsEnabled || detailStarted) return;
                const unsent = ttsFullText.slice(ttsSentBuffer.length);
                const sentenceEnd = unsent.search(/[.!?。]\s/);
                if (sentenceEnd < 0) return;
                const sendUpTo = ttsSentBuffer.length + sentenceEnd + 1;
                const chunk = ttsFullText.slice(ttsSentBuffer.length, sendUpTo).trim();
                if (!chunk) return;
                ttsQueue.push(fetchAudio(chunk));
                ttsSentBuffer = ttsFullText.slice(0, sendUpTo);
            };

            const applyDedup = (incoming: UiBlock, prev: UiBlock[]): UiBlock[] | null => {
                if (incoming.type === "text_with_image") {
                    const url = incoming.image.url;
                    return prev.filter(b => !(b.type === "image" && b.url === url));
                }
                if (incoming.type === "image") {
                    const already = prev.some(
                        b => b.type === "text_with_image" && b.image.url === incoming.url,
                    );
                    if (already) return null;
                }
                return prev;
            };

            streamLoop: while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                const chunk = decoder.decode(value, { stream: true });
                for (const line of chunk.split("\n")) {
                    if (!line.startsWith("data: ")) continue;
                    const data = line.slice(6).trim();
                    if (data === "[DONE]") break streamLoop;
                    let parsed: any;
                    try {
                        parsed = JSON.parse(data);
                    } catch {
                        continue; // partial JSON
                    }

                    if (parsed.block_start) {
                        const { id, type } = parsed.block_start;
                        if (type === "text") {
                            const textBlock: UiBlock = { id, type: "text", content: "", streaming: true };
                            blocksById.set(id, textBlock);
                            history[msgIdx].blocks = [...history[msgIdx].blocks, textBlock];
                            if (ttsTrackedBlockId === null) ttsTrackedBlockId = id;
                            pushLog("evt", `block_start:text ${id}`);
                            if (chatMode) await scrollMessages();
                        }
                    } else if (parsed.token) {
                        const { id, delta } = parsed.token;
                        const block = blocksById.get(id);
                        if (block && block.type === "text") {
                            const updated: UiBlock = { ...block, content: block.content + delta };
                            blocksById.set(id, updated);
                            history[msgIdx].blocks = history[msgIdx].blocks.map(b =>
                                b.id === id ? updated : b,
                            );
                            tokenCount += 1;
                            if (tokenCount - tokenBatchStart >= TOKEN_BATCH) {
                                pushLog("evt", `tokens ${tokenBatchStart + 1}..${tokenCount}`);
                                tokenBatchStart = tokenCount;
                            }
                            if (id === ttsTrackedBlockId) {
                                if (!detailStarted && updated.content.includes(DETAIL_MARKER)) {
                                    detailStarted = true;
                                    ttsFullText = updated.content.split(DETAIL_MARKER)[0].trim();
                                } else if (!detailStarted) {
                                    ttsFullText = updated.content;
                                }
                                tryAppendTts();
                            }
                            // Форсируем рендер после каждого токена — иначе Svelte 5 батчит
                            // обновления $state и при быстром SSE-chunk'е весь текст появляется
                            // «залпом» после обработки всего чанка.
                            await tick();
                            if (chatMode) await scrollMessages();
                        }
                    } else if (parsed.block_end) {
                        const { id } = parsed.block_end;
                        const block = blocksById.get(id);
                        if (block && block.type === "text") {
                            const updated: UiBlock = { ...block, streaming: false };
                            blocksById.set(id, updated);
                            history[msgIdx].blocks = history[msgIdx].blocks.map(b =>
                                b.id === id ? updated : b,
                            );
                            pushLog("evt", `block_end ${id}`);
                        }
                    } else if (parsed.block) {
                        const incoming = parsed.block as UiBlock;
                        const next = applyDedup(incoming, history[msgIdx].blocks);
                        if (next === null) {
                            pushLog("evt", `block:${incoming.type} dedup`);
                        } else {
                            history[msgIdx].blocks = [...next, incoming];
                            blocksById.set(incoming.id, incoming);
                            pushLog("evt", `block:${incoming.type}`);
                            if (chatMode) await scrollMessages();
                        }
                    } else if (parsed.sources) {
                        pendingSources = parsed.sources;
                        pushLog("evt", `sources[${parsed.sources.length}]`);
                    } else if (parsed.suggestions) {
                        followUps = parsed.suggestions;
                        pushLog("evt", `suggestions[${parsed.suggestions.length}]`);
                    } else if (parsed.error) {
                        pushLog("err", `stream error: ${parsed.error}`);
                        throw new Error(parsed.error);
                    }
                }
            }

            pushLog("res", `chat stream done · ${tokenCount} tokens`);

            if (ttsEnabled && !detailStarted) {
                const remaining = ttsFullText.slice(ttsSentBuffer.length).trim();
                if (remaining) ttsQueue.push(fetchAudio(remaining));
            }

            // Защита: если сервер не закрыл text-блок явно, гасим streaming-флаги.
            history[msgIdx].blocks = history[msgIdx].blocks.map(b => {
                if (b.type === "text" && b.streaming) {
                    const closed = { ...b, streaming: false };
                    blocksById.set(b.id, closed);
                    return closed;
                }
                return b;
            });
            history[msgIdx].sources = pendingSources;
            pendingSources = [];
            streamingIdx = null;
            persistChat();
            if (chatMode) await scrollMessages();

            // Play all queued TTS segments sequentially
            if (ttsEnabled && ttsQueue.length > 0) {
                state = "speaking";
                try {
                    for (const blobPromise of ttsQueue) {
                        await playBlob(await blobPromise);
                    }
                } catch {
                    /* playback error — ignore */
                }
            }
            state = "idle";
        } catch (e: unknown) {
            errorMessage =
                e instanceof Error ? e.message : "Что-то пошло не так";
            streamingIdx = null;
            const hasAnyText = history[msgIdx]?.blocks?.some(
                b => b.type === "text" && b.content.trim().length > 0,
            );
            if (!hasAnyText) {
                history = history.slice(0, msgIdx);
            }
            state = "error";
        }
    }

    async function handleSubmit() {
        const q = inputText.trim();
        if (!q || state === "thinking") return;
        if (q.startsWith("/") && tryExecuteSlashFromInput()) return;
        await submitQuery(q);
    }

    // ─── TTS ──────────────────────────────────────────────────────────────────

    function toggleTts() {
        ttsEnabled = !ttsEnabled;
        localStorage.setItem("tts", ttsEnabled ? "on" : "off");
    }

    async function fetchAudio(text: string): Promise<Blob> {
        pushLog("req", `POST /api/tts len=${text.length}`);
        const response = await fetch(`${BACKEND_URL}/api/tts`, {
            method: "POST",
            headers: { "Content-Type": "application/json", ...(await authHeaders()) },
            body: JSON.stringify({ text }),
        });
        if (!response.ok) {
            pushLog("err", `tts ${response.status}`);
            throw new Error(`TTS error: ${response.status}`);
        }
        const blob = await response.blob();
        pushLog("res", `tts 200 · ${Math.round(blob.size / 1024)}kb`);
        return blob;
    }

    function playBlob(blob: Blob): Promise<void> {
        return new Promise((resolve, reject) => {
            const url = URL.createObjectURL(blob);
            currentAudio = new Audio(url);
            currentAudio.onended = () => {
                URL.revokeObjectURL(url);
                currentAudio = null;
                resolve();
            };
            currentAudio.onerror = () => {
                URL.revokeObjectURL(url);
                currentAudio = null;
                reject();
            };
            currentAudio.play().catch(reject);
        });
    }

    // ─── ASR ──────────────────────────────────────────────────────────────────

    async function handleMic() {
        if (state === "listening") {
            stopRecording();
            return;
        }
        if (state !== "idle") return;
        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: true,
            });
            audioChunks = [];
            mediaRecorder = new MediaRecorder(stream);
            mediaRecorder.ondataavailable = (e) => {
                if (e.data.size > 0) audioChunks.push(e.data);
            };
            mediaRecorder.onstop = async () => {
                stopVoiceAnalysis();
                stream.getTracks().forEach((t) => t.stop());
                await transcribeAudio(
                    new Blob(audioChunks, { type: "audio/webm" }),
                );
            };
            startVoiceAnalysis(stream);
            mediaRecorder.start();
            state = "listening";
        } catch {
            errorMessage = "Нет доступа к микрофону";
            state = "error";
        }
    }

    function stopRecording() {
        mediaRecorder?.stop();
        mediaRecorder = null;
        state = "thinking";
    }

    async function transcribeAudio(blob: Blob) {
        try {
            const fd = new FormData();
            fd.append("audio", blob, "audio.webm");
            pushLog("req", `POST /api/asr · ${Math.round(blob.size / 1024)}kb`);
            const response = await fetch(`${BACKEND_URL}/api/asr`, {
                method: "POST",
                headers: { ...(await authHeaders()) },
                body: fd,
            });
            if (!response.ok) {
                pushLog("err", `asr ${response.status}`);
                throw new Error(`ASR error: ${response.status}`);
            }
            const { text } = await response.json();
            pushLog("res", `asr 200 → "${(text ?? "").slice(0, 24)}${(text ?? "").length > 24 ? "…" : ""}"`);
            if (text?.trim()) {
                await submitQuery(text.trim());
            } else {
                state = "idle";
            }
        } catch {
            errorMessage = "Ошибка распознавания речи";
            state = "error";
        }
    }

    // ─── Stop ─────────────────────────────────────────────────────────────────

    function handleStop() {
        currentAudio?.pause();
        currentAudio = null;
        mediaRecorder?.stop();
        mediaRecorder = null;
        stopVoiceAnalysis();
        state = "idle";
        streamingIdx = null;
        errorMessage = "";
    }

    function handleKeydown(e: KeyboardEvent) {
        // Slash palette navigation takes priority
        if (slashOpen && slashMatches.length > 0) {
            if (e.key === "ArrowDown") {
                e.preventDefault();
                slashIdx = (slashIdx + 1) % slashMatches.length;
                return;
            }
            if (e.key === "ArrowUp") {
                e.preventDefault();
                slashIdx = (slashIdx - 1 + slashMatches.length) % slashMatches.length;
                return;
            }
            if (e.key === "Tab") {
                e.preventDefault();
                inputText = slashMatches[slashIdx].name;
                slashQuery = inputText;
                slashIdx = 0;
                return;
            }
            if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                if (tryExecuteSlashFromInput()) return;
            }
            if (e.key === "Escape") {
                e.preventDefault();
                slashOpen = false;
                return;
            }
        }
        if (e.key === "Tab" && ghostText) {
            e.preventDefault();
            acceptAutocomplete();
            return;
        }
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            handleSubmit();
        }
        if (e.key === "Escape" && ghostText) {
            clearAutocomplete();
        }
    }

    function handleInput(e: Event) {
        const el = e.currentTarget as HTMLTextAreaElement;
        autoResize(el);
        updateSlashState(inputText);
        if (!slashOpen) scheduleAutocomplete(inputText);
        else clearAutocomplete();
    }

    // ─── Theme ────────────────────────────────────────────────────────────────

    type ThemeMode = "auto" | "light" | "dark";
    let themeMode: ThemeMode = $state("auto");

    onMount(() => {
        themeMode = (localStorage.getItem("theme") as ThemeMode) || "auto";
    });

    function toggleTheme() {
        const cycle: ThemeMode[] = ["auto", "light", "dark"];
        themeMode = cycle[(cycle.indexOf(themeMode) + 1) % 3];
        localStorage.setItem("theme", themeMode);
        const sys = window.matchMedia("(prefers-color-scheme: light)").matches
            ? "light"
            : "dark";
        document.documentElement.setAttribute(
            "data-theme",
            themeMode === "auto" ? sys : themeMode,
        );
    }

    // ─── Textarea auto-grow ────────────────────────────────────────────────────

    function autoResize(el: HTMLTextAreaElement) {
        el.style.height = "auto";
        el.style.height = Math.min(el.scrollHeight, 120) + "px";
    }

    // ─── Sources ──────────────────────────────────────────────────────────────

    function isCodeSource(src: string) {
        return src.startsWith("code/");
    }

    function sourceLabel(src: string): string {
        const name = src.split("/").pop() ?? src;
        if (src.startsWith("code/")) return name;
        const base = name.replace(/\.md$/, "");
        const m = base.match(/^(\d+)-(.+)$/);
        if (m) {
            const dir = src.split("/")[0];
            if (dir === "adr") return `ADR-${parseInt(m[1], 10)}`;
            return m[2]
                .replace(/-/g, " ")
                .replace(/^\w/, (c) => c.toUpperCase());
        }
        return base.replace(/-/g, " ").replace(/^\w/, (c) => c.toUpperCase());
    }

    function openSourceDoc(src: string) {
        if (isCodeSource(src)) {
            openModal("code", src.slice(5));
            return;
        }
        openModal("docs", src);
    }

    function handleContentClick(e: MouseEvent) {
        const target = e.target as HTMLElement;

        const badge = target.closest(".cite-badge") as HTMLElement | null;
        if (badge) {
            const idx = parseInt(badge.dataset.sourceIdx ?? "", 10);
            // In chat mode, find which .msg it belongs to
            if (chatMode) {
                const msgEl = badge.closest(".msg");
                if (!msgEl || !messagesEl) return;
                const msgIndex = Array.from(
                    messagesEl.querySelectorAll(".msg"),
                ).indexOf(msgEl);
                if (msgIndex < 0 || msgIndex >= history.length) return;
                const src = history[msgIndex].sources?.[idx];
                if (src) openSourceDoc(src);
            } else {
                // Inline mode — last message
                const last = history[history.length - 1];
                const src = last?.sources?.[idx];
                if (src) openSourceDoc(src);
            }
            return;
        }

        const link = target.closest("a") as HTMLAnchorElement | null;
        if (link) {
            const href = link.getAttribute("href") ?? "";
            if (
                /^https?:\/\//.test(href) &&
                !href.includes(window.location.host)
            )
                return;
            const docPath = href
                .replace(/^https?:\/\/[^/]+/, "")
                .replace(/^\//, "");
            if (!docPath) return;
            if (
                /\.(md|kt|ts|svelte|css|json|toml|yaml|yml|tsx|jsx|js)$/.test(
                    docPath,
                )
            ) {
                e.preventDefault();
                if (
                    docPath.startsWith("code/") ||
                    docPath.startsWith("server/") ||
                    docPath.startsWith("app/") ||
                    docPath.startsWith("scripts/")
                ) {
                    const codePath = docPath.startsWith("code/")
                        ? docPath
                        : `code/${docPath}`;
                    openSourceDoc(codePath);
                } else {
                    openSourceDoc(docPath);
                }
            }
        }
    }

    const stateHints: Partial<Record<PanelState, string>> = {
        listening: "Слушаю…",
    };

    // ─── Derived ──────────────────────────────────────────────────────────────

    let lastMsg = $derived(
        history.length > 0 ? history[history.length - 1] : null,
    );
    let activeSuggestions = $derived(
        (history.length === 0
            ? suggestions
            : followUps.length > 0
              ? followUps
              : suggestions
        ).slice(0, 2),
    );
</script>

<!-- ─── Full-screen orb background ──────────────────────────────────────── -->
<div class="orb-bg" class:chat-mode={chatMode} class:matrix={matrixMode} aria-hidden="true">
    {#if debugMode}
        <DebugLogStream />
        <!-- Селектор режима агента (ADR-027). Виден только в debug-режиме — обычный посетитель получает agentic по дефолту. -->
        <div class="agent-mode-switch" aria-label="Режим работы агента">
            <span class="label">mode:</span>
            <button
                type="button"
                class:active={agentMode === "agentic"}
                onclick={() => setAgentMode("agentic")}
                title="Карта корпуса + навигационные тулы. Дёшево, прозрачно."
            >agentic</button>
            <button
                type="button"
                class:active={agentMode === "full_context"}
                onclick={() => setAgentMode("full_context")}
                title="Весь корпус в промпте. Дорого cold, дёшево warm."
            >full-ctx</button>
            <button
                type="button"
                class:active={agentMode === "rag"}
                onclick={() => setAgentMode("rag")}
                title="Legacy: HyDE + BM25 + RRF retrieval."
            >rag</button>
        </div>
    {/if}
    <CosmicOrb
        {state}
        centerY={chatMode ? 0 : 0.07}
        debug={debugMode}
        pulseAt={orbPulseNonce}
        {voiceLevel}
        ondebugclose={disableDebug}
    />
</div>

{#if matrixMode}
    <canvas class="matrix-rain" bind:this={matrixCanvas} aria-hidden="true"></canvas>
{/if}

<!-- Клик-таргет: круг над центром орба.
     clip-path: circle делает кликабельной только круглую зону —
     остальное проходит к .interface. В chat mode отключаем. -->
{#if !chatMode}
    <button
        type="button"
        class="orb-click"
        style="--cy: {0.07}"
        aria-label="Орб"
        onclick={triggerOrbClick}
    ></button>
{/if}

{#if promoMessage}
    <div class="promo-toast" aria-live="polite">{promoMessage}</div>
{/if}

<!-- ─── Header (full-width) ──────────────────────────────────────────────── -->
<header class="top-bar">
    <div class="top-bar-inner">
        <div class="identity">
            <span class="name">Sergey Knyazev</span>
            <span class="role"
                >› Technical Lead · Architect<span class="cursor">_</span></span
            >
        </div>
        <div class="top-actions">
            {#if chatMode}
                <button
                    class="icon-btn"
                    onclick={closeChatMode}
                    aria-label="Свернуть чат"
                    title="Свернуть"
                >
                    <svg
                        width="14"
                        height="14"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                        stroke-linecap="round"
                    >
                        <polyline points="4 14 10 14 10 20" />
                        <polyline points="20 10 14 10 14 4" />
                        <line x1="14" y1="10" x2="21" y2="3" />
                        <line x1="3" y1="21" x2="10" y2="14" />
                    </svg>
                </button>
            {/if}
            <button
                class="icon-btn"
                onclick={() => openModal("timeline")}
                aria-label="История проекта"
                title="История"
            >
                <svg
                    width="14"
                    height="14"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                >
                    <circle cx="12" cy="12" r="10" />
                    <polyline points="12 6 12 12 16 14" />
                </svg>
            </button>
            <button
                class="icon-btn"
                onclick={() => openModal("code")}
                aria-label="Код проекта"
                title="Код"
            >
                <svg
                    width="14"
                    height="14"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                >
                    <polyline points="16 18 22 12 16 6" />
                    <polyline points="8 6 2 12 8 18" />
                </svg>
            </button>
            <button
                class="icon-btn"
                onclick={() => openModal("docs")}
                aria-label="Документация"
                title="Документация"
            >
                <svg
                    width="15"
                    height="15"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                >
                    <path
                        d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"
                    />
                    <polyline points="14 2 14 8 20 8" />
                    <line x1="16" y1="13" x2="8" y2="13" />
                    <line x1="16" y1="17" x2="8" y2="17" />
                    <line x1="10" y1="9" x2="8" y2="9" />
                </svg>
            </button>
            <button class="icon-btn" onclick={toggleTheme} aria-label="Тема">
                {#if themeMode === "light"}
                    <svg
                        width="14"
                        height="14"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                        stroke-linecap="round"
                    >
                        <circle cx="12" cy="12" r="5" />
                        <line x1="12" y1="1" x2="12" y2="3" /><line
                            x1="12"
                            y1="21"
                            x2="12"
                            y2="23"
                        />
                        <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" /><line
                            x1="18.36"
                            y1="18.36"
                            x2="19.78"
                            y2="19.78"
                        />
                        <line x1="1" y1="12" x2="3" y2="12" /><line
                            x1="21"
                            y1="12"
                            x2="23"
                            y2="12"
                        />
                        <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" /><line
                            x1="18.36"
                            y1="5.64"
                            x2="19.78"
                            y2="4.22"
                        />
                    </svg>
                {:else if themeMode === "dark"}
                    <svg
                        width="14"
                        height="14"
                        viewBox="0 0 24 24"
                        fill="currentColor"
                    >
                        <path
                            d="M21 12.79A9 9 0 1 1 11.21 3a7 7 0 0 0 9.79 9.79z"
                        />
                    </svg>
                {:else}
                    <svg
                        width="14"
                        height="14"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                        stroke-linecap="round"
                    >
                        <circle cx="12" cy="12" r="5" />
                        <line x1="12" y1="1" x2="12" y2="3" />
                        <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
                        <line x1="1" y1="12" x2="3" y2="12" />
                        <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
                        <line x1="12" y1="21" x2="12" y2="23" />
                        <path d="M17 12a5 5 0 0 1-5 5" stroke-dasharray="2 2" />
                    </svg>
                {/if}
            </button>
        </div>
    </div>
</header>

<div class="interface" data-mode={chatMode ? "chat" : "inline"}>
    {#if chatMode}
        <!-- ═══════════════════════════════════════════════════════════════════════ -->
        <!-- ─── CHAT MODE ─────────────────────────────────────────────────────── -->
        <!-- ═══════════════════════════════════════════════════════════════════════ -->
        <!-- svelte-ignore a11y_click_events_have_key_events -->
        <!-- svelte-ignore a11y_no_static_element_interactions -->
        <div
            class="messages"
            bind:this={messagesEl}
            onclick={handleContentClick}
        >
            {#each history as msg, i (i)}
                {@const isStreaming = streamingIdx === i}
                <div class="msg">
                    <div class="msg-question">{msg.question}</div>
                    {#if isStreaming && msg.blocks.length === 0}
                        <div class="msg-body md streaming">
                            <span class="state-hint">Думаю…</span>
                        </div>
                    {:else}
                        <div class="msg-body">
                            {#each msg.blocks as block (block.id)}
                                {#if block.type === "text"}
                                    <TextBlock
                                        id={block.id}
                                        content={block.content}
                                        streaming={block.streaming ?? false}
                                        sources={msg.sources}
                                    />
                                {:else if block.type === "image"}
                                    <ImageBlock
                                        url={block.url}
                                        caption={block.caption}
                                        alt={block.alt}
                                        link={block.link}
                                    />
                                {:else if block.type === "image_gallery"}
                                    <ImageGalleryBlock images={block.images} />
                                {:else if block.type === "link_list"}
                                    <LinkListBlock items={block.items} />
                                {:else if block.type === "text_with_image"}
                                    <TextWithImageBlock
                                        text={block.text}
                                        image={block.image}
                                        imagePosition={block.imagePosition}
                                    />
                                {/if}
                            {/each}
                        </div>
                    {/if}
                    {#if !isStreaming && msg.sources?.length}
                        <div class="msg-sources">
                            {#each msg.sources as src, si}
                                <button
                                    class="source-chip"
                                    onclick={() => openSourceDoc(src)}
                                >
                                    <span class="source-num">{si + 1}</span>
                                    {sourceLabel(src)}
                                </button>
                            {/each}
                        </div>
                    {/if}
                </div>
            {/each}
            {#if state === "error"}
                <div class="msg">
                    <div class="msg-error">
                        {errorMessage ||
                            "Что-то пошло не так. Попробуйте ещё раз."}
                    </div>
                </div>
            {/if}
        </div>
    {:else}
        <!-- ═══════════════════════════════════════════════════════════════════════ -->
        <!-- ─── INLINE MODE ───────────────────────────────────────────────────── -->
        <!-- ═══════════════════════════════════════════════════════════════════════ -->
        <!-- svelte-ignore a11y_click_events_have_key_events -->
        <!-- svelte-ignore a11y_no_static_element_interactions -->
        <div class="inline-area" onclick={handleContentClick}>
            {#if stateHints[state]}
                <!-- State hint: listening -->
                <div class="orb-hint">
                    <div class="state-hint">{stateHints[state]}</div>
                </div>
            {:else if lastMsg}
                <!-- Last answer (inline) — also covers live streaming into the last msg.
                     Inline режим — text и text_with_image (компактная карточка
                     фото + текст). Чистые image / image_gallery / link_list
                     остаются только в развёрнутом чате. -->
                {@const isStreaming =
                    streamingIdx !== null && streamingIdx === history.length - 1}
                {@const inlineBlocks = lastMsg.blocks.filter(
                    b => b.type === "text" || b.type === "text_with_image",
                )}
                <div class="inline-answer">
                    <div class="inline-question">{lastMsg.question}</div>
                    {#if isStreaming && inlineBlocks.length === 0}
                        <div class="msg-body md streaming">
                            <span class="state-hint">Думаю…</span>
                        </div>
                    {:else}
                        <div class="msg-body inline-body">
                            {#each inlineBlocks as block (block.id)}
                                {#if block.type === "text"}
                                    <TextBlock
                                        id={block.id}
                                        content={block.content}
                                        streaming={block.streaming ?? false}
                                        sources={lastMsg.sources}
                                    />
                                {:else if block.type === "text_with_image"}
                                    <TextWithImageBlock
                                        text={block.text}
                                        image={block.image}
                                        imagePosition={block.imagePosition}
                                    />
                                {/if}
                            {/each}
                        </div>
                    {/if}
                </div>
            {:else}
                <!-- Welcome -->
                <div class="orb-hint">
                    <div class="state-hint welcome">
                        Это AI-агент, обученный на резюме, коде и блоге Сергея
                        Князева (Technical Lead / Architect). Задайте любой
                        вопрос о его опыте — или нажмите на подсказку ниже.
                    </div>
                </div>
            {/if}

            {#if state === "error"}
                <div class="inline-error">
                    <div class="msg-error">
                        {errorMessage ||
                            "Что-то пошло не так. Попробуйте ещё раз."}
                    </div>
                </div>
            {/if}
        </div>
    {/if}

    <!-- ─── Mode toggle ─────────────────────────────────────────────────────── -->
    {#if !chatMode && history.length > 0 && state === "idle"}
        <div class="mode-toggle-row">
            <button class="mode-toggle" onclick={openChatMode}>
                <svg
                    width="12"
                    height="12"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                >
                    <path
                        d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
                    />
                </svg>
                Открыть диалог ({history.length})
            </button>
        </div>
    {/if}

    <!-- ─── Composer ────────────────────────────────────────────────────────── -->
    <div class="composer">
        <div class="composer-inner" class:focused={false}>
            {#if slashOpen && slashMatches.length > 0}
                <div class="slash-palette" role="listbox" bind:this={slashListEl}>
                    <div class="slash-hint">↑↓ выбрать · Tab дополнить · Enter выполнить · Esc закрыть</div>
                    {#each slashMatches as cmd, i}
                        <button
                            class="slash-item"
                            class:active={i === slashIdx}
                            onmouseenter={() => (slashIdx = i)}
                            onclick={() => executeSlash(cmd)}
                            role="option"
                            aria-selected={i === slashIdx}
                        >
                            <span class="slash-name">{cmd.name}</span>
                            <span class="slash-desc">{cmd.description}</span>
                        </button>
                    {/each}
                </div>
            {:else if state === "idle" && !inputText.trim() && activeSuggestions.length > 0}
                <div class="composer-suggestions">
                    {#each activeSuggestions as s}
                        <button class="chip" onclick={() => submitQuery(s)}
                            >{s}</button
                        >
                    {/each}
                </div>
            {/if}
            <div class="input-row">
                <div class="input-wrap">
                    {#if ghostText && inputText}
                        <div class="ghost-overlay" aria-hidden="true">
                            <span class="ghost-hidden">{inputText}</span><span
                                class="ghost-completion">{ghostText}</span
                            >
                        </div>
                    {/if}
                    <textarea
                        bind:this={inputEl}
                        class="composer-input"
                        placeholder="Задайте вопрос…"
                        rows="1"
                        bind:value={inputText}
                        onkeydown={handleKeydown}
                        oninput={handleInput}
                        disabled={state === "thinking"}
                        aria-label="Введите вопрос"
                    ></textarea>
                </div>
                <div class="composer-actions">
                    {#if state === "speaking" || state === "listening"}
                        <button
                            class="action-btn stop-btn"
                            onclick={handleStop}
                            aria-label="Остановить"
                        >
                            <svg
                                width="14"
                                height="14"
                                viewBox="0 0 16 16"
                                fill="currentColor"
                            >
                                <rect
                                    x="3"
                                    y="3"
                                    width="10"
                                    height="10"
                                    rx="1"
                                />
                            </svg>
                        </button>
                    {:else}
                        <button
                            class="action-btn tts-btn"
                            class:off={!ttsEnabled}
                            onclick={toggleTts}
                            aria-label={ttsEnabled
                                ? "Отключить озвучивание"
                                : "Включить озвучивание"}
                            title={ttsEnabled
                                ? "Озвучивание вкл."
                                : "Озвучивание выкл."}
                        >
                            {#if ttsEnabled}
                                <svg
                                    width="14"
                                    height="14"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    stroke-width="2"
                                    stroke-linecap="round"
                                    stroke-linejoin="round"
                                >
                                    <polygon
                                        points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"
                                        fill="currentColor"
                                    />
                                    <path d="M15.54 8.46a5 5 0 0 1 0 7.07" />
                                    <path d="M19.07 4.93a10 10 0 0 1 0 14.14" />
                                </svg>
                            {:else}
                                <svg
                                    width="14"
                                    height="14"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    stroke-width="2"
                                    stroke-linecap="round"
                                    stroke-linejoin="round"
                                >
                                    <polygon
                                        points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"
                                        fill="currentColor"
                                    />
                                    <line x1="23" y1="9" x2="17" y2="15" />
                                    <line x1="17" y1="9" x2="23" y2="15" />
                                </svg>
                            {/if}
                        </button>
                        <button
                            class="action-btn mic-btn"
                            class:active={state === "listening"}
                            onclick={handleMic}
                            aria-label="Голосовой ввод"
                            disabled={state === "thinking"}
                        >
                            <svg
                                width="14"
                                height="14"
                                viewBox="0 0 16 16"
                                fill="currentColor"
                            >
                                <rect x="5" y="1" width="6" height="9" rx="3" />
                                <path
                                    d="M2.5 8a5.5 5.5 0 0 0 11 0"
                                    stroke="currentColor"
                                    stroke-width="1.5"
                                    fill="none"
                                    stroke-linecap="round"
                                />
                                <line
                                    x1="8"
                                    y1="13.5"
                                    x2="8"
                                    y2="15.5"
                                    stroke="currentColor"
                                    stroke-width="1.5"
                                    stroke-linecap="round"
                                />
                            </svg>
                        </button>
                        <button
                            class="action-btn send-btn"
                            onclick={handleSubmit}
                            aria-label="Отправить"
                            disabled={state === "thinking" || !inputText.trim()}
                        >
                            <svg
                                width="14"
                                height="14"
                                viewBox="0 0 16 16"
                                fill="currentColor"
                            >
                                <path d="M2 8L14 2L10 8L14 14L2 8Z" />
                            </svg>
                        </button>
                    {/if}
                </div>
            </div>
        </div>
    </div>
</div>

<!-- ─── Content Modal ────────────────────────────────────────────────────── -->
<ContentModal
    open={modalOpen}
    initialTab={modalTab}
    openPath={modalOpenPath}
    imageView={imagePreviewState.value}
    onclose={closeModal}
/>

<style>
    /* ─── Agent mode switch (debug only, ADR-027) ──────────────────────────── */
    .agent-mode-switch {
        position: absolute;
        top: 12px;
        right: 12px;
        z-index: 3;
        display: flex;
        gap: 4px;
        align-items: center;
        padding: 4px 8px;
        background: color-mix(in srgb, var(--color-bg) 72%, transparent);
        backdrop-filter: blur(8px);
        -webkit-backdrop-filter: blur(8px);
        border: 1px solid color-mix(in srgb, var(--color-accent) 28%, transparent);
        border-radius: 8px;
        font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
        font-size: 11px;
        line-height: 1;
        pointer-events: auto;
    }
    .agent-mode-switch .label {
        color: color-mix(in srgb, var(--color-accent) 60%, transparent);
        padding-right: 4px;
    }
    .agent-mode-switch button {
        padding: 4px 8px;
        background: transparent;
        border: 1px solid transparent;
        border-radius: 4px;
        color: color-mix(in srgb, var(--color-accent) 55%, transparent);
        font-family: inherit;
        font-size: inherit;
        cursor: pointer;
        transition: background 120ms ease, color 120ms ease, border-color 120ms ease;
    }
    .agent-mode-switch button:hover {
        color: var(--color-accent);
        border-color: color-mix(in srgb, var(--color-accent) 35%, transparent);
    }
    .agent-mode-switch button.active {
        background: color-mix(in srgb, var(--color-accent) 18%, transparent);
        border-color: color-mix(in srgb, var(--color-accent) 55%, transparent);
        color: var(--color-accent);
    }

    /* ═══════════════════════════════════════════════════════════════════════════ */
    /* ─── Layout ─────────────────────────────────────────────────────────────── */
    /* ═══════════════════════════════════════════════════════════════════════════ */

    /* ─── Top bar (full-width masthead) ────────── */

    .top-bar {
        position: relative;
        z-index: 2;
        width: 100%;
        align-self: stretch;
        flex-shrink: 0;
        padding: 0 clamp(16px, 4vw, 48px);
        background: color-mix(in srgb, var(--color-bg) 55%, transparent);
        backdrop-filter: blur(12px) saturate(1.1);
        -webkit-backdrop-filter: blur(12px) saturate(1.1);
    }

    .top-bar-inner {
        max-width: none;
        margin: 0;
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 14px 0 12px;
    }

    .interface {
        position: relative;
        z-index: 1;
        width: 100%;
        max-width: 760px;
        margin: 0 auto;
        flex: 1;
        display: flex;
        flex-direction: column;
        padding: 0 24px 20px;
        overflow: hidden;
    }

    .identity {
        display: flex;
        flex-direction: column;
        gap: 3px;
    }

    .name {
        font-size: 16px;
        font-weight: 700;
        color: var(--color-text-heading);
        letter-spacing: -0.01em;
        text-shadow: 0 0 40px var(--color-accent-glow);
    }

    .role {
        font-size: 10px;
        color: var(--color-accent);
        font-family: var(--font-mono);
        letter-spacing: 0.06em;
        opacity: 0.75;
        display: flex;
        align-items: center;
        gap: 4px;
    }

    .cursor {
        animation: blink 1.2s step-end infinite;
        color: var(--color-accent);
    }

    @keyframes blink {
        0%,
        100% {
            opacity: 1;
        }
        50% {
            opacity: 0;
        }
    }

    .top-actions {
        display: flex;
        gap: 8px;
    }

    .icon-btn {
        width: 30px;
        height: 30px;
        border-radius: 7px;
        border: 1px solid var(--color-border);
        background: none;
        color: var(--color-text-dim);
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        transition:
            color var(--transition),
            border-color var(--transition);
    }

    .icon-btn:hover {
        color: var(--color-accent);
        border-color: var(--color-accent);
    }

    /* ═══════════════════════════════════════════════════════════════════════════ */
    /* ─── INLINE MODE ────────────────────────────────────────────────────────── */
    /* ═══════════════════════════════════════════════════════════════════════════ */

    .inline-area {
        flex: 1;
        display: flex;
        flex-direction: column;
        justify-content: flex-end;
        overflow-y: auto;
        padding: 12px 0;
        gap: 12px;
    }

    .inline-answer {
        display: flex;
        flex-direction: column;
        gap: 8px;
        width: 100%;
        max-width: 100%;
        animation: fadeIn 0.3s ease;
    }

    .inline-question {
        font-size: 12px;
        font-family: var(--font-mono);
        color: var(--color-text-dim);
        letter-spacing: 0.02em;
        padding-bottom: 4px;
    }

    .inline-error {
        width: 100%;
        text-align: center;
        padding-top: 8px;
    }

    /* ─── Orb hint (inline mode, прижат к низу как inline-answer) ───── */

    .orb-hint {
        display: flex;
        justify-content: center;
        pointer-events: none;
        width: 100%;
        animation: fadeIn 0.3s ease;
    }

    .state-hint {
        font-size: 10px;
        font-family: var(--font-mono);
        color: var(--color-state-hint);
        letter-spacing: 0.08em;
        height: 16px;
        transition: opacity 0.4s ease;
    }

    .state-hint.welcome {
        font-size: 12px;
        line-height: 1.6;
        letter-spacing: 0.02em;
        max-width: 360px;
        text-align: center;
        height: auto;
        color: var(--color-text-dim);
    }

    /* ═══════════════════════════════════════════════════════════════════════════ */
    /* ─── CHAT MODE ──────────────────────────────────────────────────────────── */
    /* ═══════════════════════════════════════════════════════════════════════════ */

    .messages {
        flex: 1;
        overflow-y: auto;
        overflow-x: hidden;
        padding: 12px 0 8px;
        display: flex;
        flex-direction: column;
        gap: 20px;
        scrollbar-gutter: stable;
        mask-image: linear-gradient(to bottom, transparent 0%, black 48px);
    }

    .msg {
        display: flex;
        flex-direction: column;
        gap: 8px;
    }

    .msg-question {
        align-self: flex-end;
        background: var(--color-surface);
        border: 1px solid var(--color-border);
        border-radius: 12px 12px 3px 12px;
        padding: 9px 14px;
        font-size: 14px;
        color: var(--color-text);
        max-width: 85%;
    }

    /* ADR-025: единый контейнер для блоков ответа — текст, картинки, ссылки,
       text_with_image — идут единой лентой с общим ритмом. */
    .msg-body {
        display: flex;
        flex-direction: column;
        gap: 12px;
        align-items: stretch;
    }
    .msg-body.streaming {
        align-self: flex-start;
    }
    .msg-body.inline-body {
        gap: 10px;
    }

    /* ═══════════════════════════════════════════════════════════════════════════ */
    /* ─── Answer / Markdown (shared) ─────────────────────────────────────────── */
    /* ═══════════════════════════════════════════════════════════════════════════ */

    .msg-answer {
        align-self: flex-start;
        font-size: 14px;
        line-height: 1.75;
        color: var(--color-text);
        max-width: 100%;
    }

    .msg-answer.md :global(p) {
        margin: 0 0 10px;
    }
    .msg-answer.md :global(p:last-child) {
        margin-bottom: 0;
    }
    .msg-answer.md :global(ul),
    .msg-answer.md :global(ol) {
        padding-left: 18px;
        margin: 0 0 10px;
    }
    .msg-answer.md :global(li) {
        margin: 3px 0;
    }
    .msg-answer.md :global(strong) {
        color: var(--color-text-heading);
        font-weight: 600;
    }
    .msg-answer.md :global(em) {
        font-style: italic;
    }
    .msg-answer.md :global(h1),
    .msg-answer.md :global(h2),
    .msg-answer.md :global(h3) {
        font-size: 14px;
        font-weight: 600;
        color: var(--color-text-heading);
        margin: 14px 0 6px;
    }
    .msg-answer.md :global(code) {
        font-family: var(--font-mono);
        font-size: 12px;
        background: var(--color-surface-hover);
        padding: 1px 5px;
        border-radius: 3px;
        color: var(--color-accent);
    }
    .msg-answer.md :global(pre) {
        background: var(--color-surface-hover);
        border: 1px solid var(--color-border);
        border-radius: 6px;
        padding: 10px 12px;
        overflow-x: auto;
        margin: 0 0 10px;
    }
    .msg-answer.md :global(pre code) {
        background: none;
        padding: 0;
        color: var(--color-text);
        font-size: 12px;
    }
    .msg-answer.md :global(blockquote) {
        border-left: 2px solid var(--color-accent);
        margin: 0 0 10px;
        padding: 2px 10px;
        color: var(--color-text-dim);
        font-style: italic;
    }
    .msg-answer.md :global(hr) {
        border: none;
        border-top: 1px solid var(--color-border);
        margin: 12px 0;
    }
    .msg-answer.md :global(a) {
        color: var(--color-accent);
        text-decoration: none;
    }
    .msg-answer.md :global(a:hover) {
        text-decoration: underline;
    }

    .msg-answer.streaming {
        color: var(--color-text-heading);
    }

    .stream-cursor {
        display: inline-block;
        color: var(--color-accent);
        animation: blink 0.8s step-end infinite;
        margin-left: 1px;
    }

    /* ─── Inline citation badges [1] [2] ──── */

    .msg-answer.md :global(.cite-badge),
    .msg-detail.md :global(.cite-badge) {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 18px;
        height: 18px;
        border-radius: 50%;
        border: none;
        background: var(--color-accent);
        color: #fff;
        font-size: 10px;
        font-weight: 700;
        font-family: var(--font-mono);
        cursor: pointer;
        vertical-align: super;
        margin: 0 1px;
        line-height: 1;
        transition:
            background var(--transition),
            transform var(--transition);
    }

    .msg-answer.md :global(.cite-badge:hover),
    .msg-detail.md :global(.cite-badge:hover) {
        background: var(--color-send-hover);
        transform: scale(1.15);
    }

    /* ─── Source chips (below answer) ──────── */

    .msg-blocks {
        display: flex;
        flex-direction: column;
        gap: 10px;
        margin-top: 10px;
    }

    .msg-sources {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        margin-top: 6px;
        padding-top: 8px;
        border-top: 1px solid var(--color-border);
    }

    .source-chip {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 5px 10px;
        border-radius: 8px;
        border: 1px solid var(--color-border);
        background: var(--color-surface);
        color: var(--color-text);
        font-size: 12px;
        font-family: var(--font-mono);
        cursor: pointer;
        text-decoration: none;
        transition:
            color var(--transition),
            border-color var(--transition),
            background var(--transition);
    }

    .source-chip:hover {
        color: var(--color-accent);
        border-color: var(--color-accent);
        background: var(--color-accent-glow);
    }

    .source-num {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 18px;
        height: 18px;
        border-radius: 50%;
        background: var(--color-accent);
        color: #fff;
        font-size: 10px;
        font-weight: 700;
        flex-shrink: 0;
    }

    /* ─── Detail expand ──────────────────────── */

    .msg-detail {
        align-self: flex-start;
        font-size: 14px;
        line-height: 1.75;
        color: var(--color-text);
        max-width: 100%;
        padding-top: 8px;
        border-top: 1px solid var(--color-border);
        animation: fadeIn 0.3s ease;
    }

    .msg-detail.md :global(p) {
        margin: 0 0 10px;
    }
    .msg-detail.md :global(p:last-child) {
        margin-bottom: 0;
    }
    .msg-detail.md :global(ul),
    .msg-detail.md :global(ol) {
        padding-left: 18px;
        margin: 0 0 10px;
    }
    .msg-detail.md :global(li) {
        margin: 3px 0;
    }
    .msg-detail.md :global(strong) {
        color: var(--color-text-heading);
        font-weight: 600;
    }
    .msg-detail.md :global(code) {
        font-family: var(--font-mono);
        font-size: 12px;
        background: var(--color-surface-hover);
        padding: 1px 5px;
        border-radius: 3px;
        color: var(--color-accent);
    }
    .msg-detail.md :global(pre) {
        background: var(--color-surface-hover);
        border: 1px solid var(--color-border);
        border-radius: 6px;
        padding: 10px 12px;
        overflow-x: auto;
        margin: 0 0 10px;
    }
    .msg-detail.md :global(pre code) {
        background: none;
        padding: 0;
        color: var(--color-text);
        font-size: 12px;
    }
    .msg-detail.md :global(a) {
        color: var(--color-accent);
        text-decoration: none;
    }
    .msg-detail.md :global(a:hover) {
        text-decoration: underline;
    }

    .detail-toggle {
        align-self: flex-start;
        background: none;
        border: none;
        color: var(--color-accent);
        font-size: 12px;
        font-family: var(--font-mono);
        cursor: pointer;
        padding: 2px 0;
        opacity: 0.8;
        transition: opacity var(--transition);
    }

    .detail-toggle:hover {
        opacity: 1;
        text-decoration: underline;
    }

    .msg-error {
        font-size: 13px;
        color: var(--color-error);
        font-family: var(--font-mono);
    }

    /* ═══════════════════════════════════════════════════════════════════════════ */
    /* ─── Orb background ─────────────────────────────────────────────────────── */
    /* ═══════════════════════════════════════════════════════════════════════════ */

    .orb-bg {
        position: fixed;
        inset: 0;
        pointer-events: none;
        z-index: 0;
        transition: opacity 0.5s ease;
    }

    .orb-bg.chat-mode {
        opacity: 0.3;
    }

    @keyframes fadeIn {
        from {
            opacity: 0;
            transform: translateY(4px);
        }
        to {
            opacity: 1;
            transform: translateY(0);
        }
    }

    .chip {
        padding: 6px 14px;
        border-radius: 20px;
        border: 1px solid var(--color-border);
        background: var(--color-surface);
        color: var(--color-text-dim);
        font-size: 12px;
        font-family: var(--font-sans);
        cursor: pointer;
        transition:
            color var(--transition),
            border-color var(--transition),
            background var(--transition);
        white-space: nowrap;
    }

    .chip:hover {
        color: var(--color-text);
        border-color: var(--color-accent);
        background: var(--color-accent-glow);
    }

    /* ─── Mode toggle ────────────────────────── */

    .mode-toggle-row {
        flex-shrink: 0;
        display: flex;
        justify-content: center;
        padding: 4px 0;
    }

    .mode-toggle {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 5px 14px;
        border-radius: 20px;
        border: 1px solid var(--color-border);
        background: none;
        color: var(--color-text-dim);
        font-size: 11px;
        font-family: var(--font-mono);
        cursor: pointer;
        transition:
            color var(--transition),
            border-color var(--transition);
    }

    .mode-toggle:hover {
        color: var(--color-accent);
        border-color: var(--color-accent);
    }

    /* ═══════════════════════════════════════════════════════════════════════════ */
    /* ─── Composer ───────────────────────────────────────────────────────────── */
    /* ═══════════════════════════════════════════════════════════════════════════ */

    .composer {
        flex-shrink: 0;
        padding-top: 10px;
    }

    .composer-inner {
        display: flex;
        flex-direction: column;
        gap: 0;
        background: var(--color-surface);
        border: 1px solid var(--color-border);
        border-radius: 14px;
        padding: 10px 12px;
        transition: border-color var(--transition);
    }

    .composer-inner:focus-within {
        border-color: var(--color-accent);
    }

    .composer-suggestions {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        padding-bottom: 8px;
        animation: fadeIn 0.3s ease;
    }

    .input-row {
        display: flex;
        align-items: flex-end;
        gap: 8px;
    }

    .input-wrap {
        flex: 1;
        position: relative;
        min-width: 0;
    }

    .composer-input {
        width: 100%;
        background: none;
        border: none;
        outline: none;
        color: var(--color-text);
        font-family: var(--font-sans);
        font-size: 14px;
        line-height: 1.5;
        resize: none;
        max-height: 120px;
        overflow-y: auto;
        position: relative;
        z-index: 1;
    }

    .composer-input::placeholder {
        color: var(--color-text-dim);
    }
    .composer-input:disabled {
        opacity: 0.5;
    }

    .ghost-overlay {
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        font-family: var(--font-sans);
        font-size: 14px;
        line-height: 1.5;
        pointer-events: none;
        white-space: pre-wrap;
        word-break: break-word;
        z-index: 0;
    }

    .ghost-hidden {
        visibility: hidden;
    }

    .ghost-completion {
        color: var(--color-text-dim);
        opacity: 0.5;
    }

    .composer-actions {
        display: flex;
        gap: 6px;
        flex-shrink: 0;
    }

    .action-btn {
        width: 30px;
        height: 30px;
        border-radius: 8px;
        border: none;
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        transition:
            background var(--transition),
            color var(--transition),
            opacity var(--transition);
    }

    .action-btn:disabled {
        opacity: 0.35;
        cursor: default;
    }

    .tts-btn {
        background: var(--color-surface-hover);
        color: var(--color-accent);
    }
    .tts-btn:hover {
        color: var(--color-text);
        background: var(--color-border);
    }
    .tts-btn.off {
        color: var(--color-text-dim);
        opacity: 0.5;
    }
    .tts-btn.off:hover {
        opacity: 0.8;
    }

    .mic-btn {
        background: var(--color-surface-hover);
        color: var(--color-text-dim);
    }
    .mic-btn:hover:not(:disabled) {
        color: var(--color-text);
        background: var(--color-border);
    }
    .mic-btn.active {
        background: var(--color-mic-bg);
        color: var(--color-mic-fg);
    }

    .send-btn {
        background: var(--color-accent);
        color: #fff;
    }
    .send-btn:hover:not(:disabled) {
        background: var(--color-send-hover);
    }

    .stop-btn {
        background: color-mix(in srgb, var(--color-error) 15%, transparent);
        color: var(--color-error);
    }
    .stop-btn:hover {
        background: rgba(255, 123, 123, 0.25);
    }

    /* ─── Slash palette ──────────────────────────────────────────────────── */

    .slash-palette {
        display: flex;
        flex-direction: column;
        gap: 2px;
        padding: 6px 4px 10px;
        max-height: 260px;
        overflow-y: auto;
        animation: fadeIn 0.15s ease;
    }

    .slash-hint {
        font-size: 9px;
        font-family: var(--font-mono);
        color: var(--color-text-dim);
        letter-spacing: 0.06em;
        padding: 0 8px 6px;
        opacity: 0.7;
    }

    .slash-item {
        display: flex;
        align-items: baseline;
        justify-content: space-between;
        gap: 12px;
        padding: 6px 10px;
        border: none;
        background: transparent;
        border-radius: 6px;
        text-align: left;
        cursor: pointer;
        transition: background var(--transition);
    }
    .slash-item:hover,
    .slash-item.active {
        background: var(--color-surface-hover);
    }
    .slash-name {
        font-family: var(--font-mono);
        font-size: 12px;
        color: var(--color-accent);
        letter-spacing: 0.02em;
    }
    .slash-desc {
        font-size: 11px;
        color: var(--color-text-dim);
        text-align: right;
    }

    /* ─── Orb click target ───────────────────────────────────────────────── */
    /* Круглый hit-area над орбом. Лежит поверх .interface по z-index, но
       clip-path: circle отсекает клики вне круга — они проходят к composer/UI. */
    .orb-click {
        position: fixed;
        left: 50%;
        top: calc(50% - var(--cy, 0) * 100vh);
        transform: translate(-50%, -50%);
        width: min(44vmin, 380px);
        height: min(44vmin, 380px);
        border: none;
        background: transparent;
        padding: 0;
        border-radius: 50%;
        clip-path: circle(50%);
        cursor: pointer;
        outline: none;
        z-index: 3;
        -webkit-tap-highlight-color: transparent;
    }
    .orb-click:focus-visible {
        box-shadow: 0 0 0 2px var(--color-accent);
    }

    /* ─── Promo toast ────────────────────────────────────────────────────── */

    .promo-toast {
        position: fixed;
        top: 72px;
        left: 50%;
        transform: translateX(-50%);
        z-index: 30;
        padding: 8px 16px;
        background: color-mix(in srgb, var(--color-bg) 88%, transparent);
        backdrop-filter: blur(10px);
        -webkit-backdrop-filter: blur(10px);
        border: 1px solid var(--color-accent);
        border-radius: 20px;
        font-family: var(--font-mono);
        font-size: 11px;
        letter-spacing: 0.06em;
        color: var(--color-accent);
        box-shadow: 0 8px 32px var(--color-accent-glow);
        animation: toastIn 0.25s ease;
        pointer-events: none;
    }

    @keyframes toastIn {
        from { opacity: 0; transform: translate(-50%, -8px); }
        to { opacity: 1; transform: translate(-50%, 0); }
    }

    /* ─── Matrix effect ──────────────────────────────────────────────────── */

    .orb-bg.matrix :global(canvas) {
        filter: hue-rotate(95deg) saturate(1.8) brightness(1.1);
        transition: filter 0.3s ease;
    }

    .matrix-rain {
        position: fixed;
        inset: 0;
        width: 100vw;
        height: 100vh;
        pointer-events: none;
        z-index: 2;
        mix-blend-mode: screen;
        animation: matrixFade 6s ease-out forwards;
    }

    @keyframes matrixFade {
        0%   { opacity: 0; }
        10%  { opacity: 1; }
        85%  { opacity: 1; }
        100% { opacity: 0; }
    }
</style>
