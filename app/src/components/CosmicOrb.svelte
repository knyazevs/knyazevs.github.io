<script lang="ts">
  import { onMount } from 'svelte';

  type OrbState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'error';
  // Rename destructure: using `state` here shadows the `$state` rune and breaks
  // Svelte 5 compilation (compiler treats `$state(...)` as store access).
  let {
    state: orbState = 'idle',
    centerY = 0,
    debug = false,
    pulseAt = 0,
    voiceLevel = 0,
    ondebugclose,
  }: {
    state: OrbState;
    centerY?: number;
    debug?: boolean;
    /** Timestamp that triggers a ripple when it changes. Parent sets it on click. */
    pulseAt?: number;
    /** Realtime mic amplitude 0..1 — drives shader voice reactivity. */
    voiceLevel?: number;
    /** Called when the user dismisses the debug panel via the × button. */
    ondebugclose?: () => void;
  } = $props();

  let _s: OrbState = 'idle';
  let _cy: number = 0;
  let _voice: number = 0;
  $effect(() => { _s = orbState; });
  $effect(() => { _cy = centerY; });
  $effect(() => { _voice = voiceLevel; });
  $effect(() => {
    if (pulseAt > 0) clickAt = performance.now();
  });

  let canvas: HTMLCanvasElement;
  let animFrame: number = 0;
  let clickAt = 0; // performance.now() of last click, 0 = none
  let fps = $state(0);
  let dbg = $state({ speed: 0, brightness: 0, pulse: 0, size: 0, tint: '0,0,0' });

  // State → shader parameters
  const PARAMS: Record<OrbState, {
    speed: number; brightness: number;
    tint: [number,number,number]; tintStr: number;
    pulse: number; size: number;
  }> = {
    idle:      { speed: 0.32, brightness: 1.00, tint: [1.00, 0.72, 0.38], tintStr: 0.15, pulse: 0.0, size: 1.00 },
    listening: { speed: 0.85, brightness: 1.30, tint: [0.45, 1.00, 0.55], tintStr: 0.55, pulse: 1.0, size: 1.08 },
    thinking:  { speed: 1.90, brightness: 1.15, tint: [1.00, 0.62, 0.22], tintStr: 0.35, pulse: 0.4, size: 1.04 },
    speaking:  { speed: 0.60, brightness: 1.55, tint: [1.00, 0.85, 0.45], tintStr: 0.38, pulse: 0.9, size: 1.12 },
    error:     { speed: 0.22, brightness: 0.82, tint: [1.00, 0.18, 0.08], tintStr: 0.72, pulse: 0.0, size: 0.90 },
  };

  // ── Vertex shader ──────────────────────────────────────────────────────────
  const VS = `#version 300 es
    in vec2 a_pos;
    void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }
  `;

  // ── Fragment shader: warm domain-warped fluid ──────────────────────────────
  const FS = `#version 300 es
    precision highp float;

    uniform float iTime;
    uniform vec2  iResolution;
    uniform float u_speed;
    uniform float u_brightness;
    uniform vec3  u_tint;
    uniform float u_tint_str;
    uniform float u_pulse;
    uniform float u_size;
    uniform vec3  u_bg;
    uniform vec2  u_mouse;      // [-1..1] relative to orb canvas center (Y flipped to GL)
    uniform vec2  u_center;     // offset of orb center in aspect-corrected uv space (y>0 → up on screen)
    uniform float u_click;      // seconds since last click; ≥10 means inactive
    uniform float u_voice;      // realtime mic amplitude 0..1 (smoothed by client)

    out vec4 fragColor;

    // ── Gradient noise ────────────────────────────────────────────────────────
    vec2 hash2(vec2 p) {
      p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
      return -1.0 + 2.0 * fract(sin(p) * 43758.5453);
    }

    float gnoise(vec2 p) {
      vec2 i = floor(p);
      vec2 f = fract(p);
      vec2 u = f * f * (3.0 - 2.0 * f);
      return mix(
        mix(dot(hash2(i            ), f            ),
            dot(hash2(i + vec2(1,0)), f - vec2(1,0)), u.x),
        mix(dot(hash2(i + vec2(0,1)), f - vec2(0,1)),
            dot(hash2(i + vec2(1,1)), f - vec2(1,1)), u.x), u.y);
    }

    // Rotation matrix for FBM octave twist
    const mat2 ROT = mat2(0.80, 0.60, -0.60, 0.80);

    float fbm(vec2 p) {
      float f = 0.0;
      f += 0.5000 * gnoise(p); p = ROT * p * 2.02;
      f += 0.2500 * gnoise(p); p = ROT * p * 2.03;
      f += 0.1250 * gnoise(p); p = ROT * p * 2.01;
      f += 0.0625 * gnoise(p);
      return f / 0.9375;
    }

    void main() {
      vec2 uv = gl_FragCoord.xy / iResolution - 0.5;
      uv.y *= iResolution.y / iResolution.x;
      uv   -= u_center;

      float rad = length(uv);

      // ── Orb shape (Gaussian falloff + pulse) ────────────────────────────────
      // Voice adds an instantaneous amplitude term on top of the slow sine pulse,
      // so the orb breathes in sync with actual mic input while recording.
      float pulse   = 1.0
                    + 0.07 * u_pulse * (0.5 + 0.5 * sin(iTime * u_speed * 2.2))
                    + 0.14 * u_voice;
      float k       = 88.0 / (u_size * u_size * pulse * pulse);
      float orb     = exp(-rad * rad * k);
      float orbHard = smoothstep(0.0, 0.012, orb);  // crisp interior mask

      // Early exit far from any visible region
      if (orb < 0.003 && rad > u_size * 0.9) {
        fragColor = vec4(u_bg, 1.0);
        return;
      }

      // ── Sphere wrap (math-only, no silhouette) ──────────────────────────────
      // Virtual sphere drives UV compression + limb darkening so fluid features
      // wrap around a curved surface. Visibility is still governed by Gaussian.
      float Rv  = 0.32 * u_size * pulse;
      float nz2 = max(1.0 - rad * rad / (Rv * Rv), 0.0);
      float nz  = sqrt(nz2);                          // 1 at apex → 0 at rim/outside
      float stretch = 1.0 / max(0.35 + 0.65 * nz, 0.30);

      // ── Domain-warped fluid ─────────────────────────────────────────────────
      float t = iTime * u_speed * 0.22;
      vec2  p = uv * 3.6 * stretch;                   // features tighten toward rim
      // Cursor parallax: features slide with cursor — orb appears to "look at" pointer.
      // Scaled small; preserves silhouette.
      p -= u_mouse * 0.18;

      // First warp layer
      vec2 q = vec2(fbm(p + t * 0.70),
                    fbm(p + vec2(5.2, 1.3) + t * 0.55));

      // Second warp layer (deeper)
      vec2 r = vec2(fbm(p + 3.5*q + vec2(1.7, 9.2) + t * 0.42),
                    fbm(p + 3.5*q + vec2(8.3, 2.8) + t * 0.35));

      float f = fbm(p + 3.0*r + t * 0.18);

      // ── Slow veil: low-freq luminance drift (parallax without geometry) ────
      // Reused below for uneven "breathing" brightness
      float slow = fbm(uv * 1.2 + vec2(-t * 0.22, t * 0.14));
      f += slow * 0.18;

      // ── Warm amber-chocolate palette ────────────────────────────────────────
      // Remap f (≈ [-0.5, 0.5]) to a nice contrast curve
      float c = clamp(f * 0.85 + 0.55, 0.0, 1.0);
      c = c * c * (3.0 - 2.0 * c);  // extra smoothstep contrast

      vec3 col0 = vec3(0.04, 0.015, 0.003);  // charred brown (shadow)
      vec3 col1 = vec3(0.48, 0.16, 0.025);   // dark amber
      vec3 col2 = vec3(0.82, 0.44, 0.08);    // warm amber
      vec3 col3 = vec3(1.00, 0.78, 0.30);    // bright gold highlight
      vec3 col4 = vec3(1.00, 0.95, 0.78);    // incandescent core (near-white peach)

      vec3 col  = mix(col0, col1, smoothstep(0.00, 0.35, c));
      col       = mix(col,  col2, smoothstep(0.28, 0.65, c));
      col       = mix(col,  col3, smoothstep(0.58, 1.00, c));

      // Bright veins where warp distortion is strongest
      float vein = smoothstep(0.55, 1.0, length(q) * 0.85);
      col += col3 * vein * 0.35;

      // ── Terrain relief ──────────────────────────────────────────────────
      // Height from fluid value f. Crests gain incandescent highlight weighted
      // by sphere apex (nz) — ridges catch internal light like molten cracks.
      // No external light direction → stays a source, not a reflector.
      float height = clamp(f * 0.5 + 0.5, 0.0, 1.0);
      float crest  = smoothstep(0.62, 0.95, height) * (0.25 + 0.55 * nz);
      col += col4 * crest * 0.45;

      // Valley self-shadow — subtle darkening where height is low & sphere is steep
      float valley = (1.0 - smoothstep(0.15, 0.55, height)) * nz;
      col *= mix(1.0, 0.78, valley * 0.45);

      // ── Incandescent core ────────────────────────────────────────────────
      // Tighter Gaussian; cools hue toward white-peach and boosts brightness
      // only where fluid is already bright → depth via temperature contrast
      float core    = exp(-rad * rad * k * 5.5);
      float coreAmt = core * smoothstep(0.35, 1.0, c);
      col  = mix(col, col4, coreAmt * 0.65);
      col += col4 * coreAmt * 0.35;

      // Subtle dark rim inside the orb edge for depth
      float rim = smoothstep(0.0, 0.3, 1.0 - orb * 3.0);
      col = mix(col, col * 0.35, rim * 0.5);

      // State tint
      col = mix(col, col * u_tint, u_tint_str * 0.42);

      // Uneven "breathing" — spatial brightness modulation from slow fbm
      // Range ≈ [0.90, 1.10]: orb tleet неравномерно, без ритмичного пульса
      col *= 1.0 + 0.22 * slow;

      // Limb darkening — star-like falloff toward rim (NOT Fresnel brightening)
      // Helps features feel curved away, reinforces sphere wrap cue
      col *= mix(0.75, 1.0, nz);

      // ── Cursor-side emission ─────────────────────────────────────────────
      // Brighten the hemisphere facing the cursor — reads as the orb leaning
      // toward the viewer's attention without introducing an external light dir.
      float mouseMag    = length(u_mouse);
      float cursorGate  = smoothstep(0.02, 0.6, mouseMag);
      vec2  cursorDir   = u_mouse / max(mouseMag, 1e-4);
      vec2  uvDir       = uv / max(rad, 1e-4);
      float side        = max(dot(uvDir, cursorDir), 0.0);
      col += col4 * side * cursorGate * nz * 0.28;

      // ── Voice reactivity ────────────────────────────────────────────────
      // Core brightens and features intensify with mic amplitude.
      col += col4 * u_voice * 0.35 * orbHard * nz;
      col *= 1.0 + u_voice * 0.22;

      // ── Click ripple ─────────────────────────────────────────────────────
      // Outward radial wave launched on click. Envelope peaks ~50ms, fades by 1.2s.
      if (u_click < 1.2) {
        float env  = smoothstep(0.0, 0.05, u_click) * (1.0 - smoothstep(0.0, 1.2, u_click));
        float wave = sin(rad * 22.0 - u_click * 14.0) * exp(-max(rad - u_click * 0.55, 0.0) * 6.0);
        col += col4 * wave * env * 0.55;
        col *= 1.0 + env * 0.18;
      }

      col *= u_brightness;

      // Reinhard + mild gamma
      col  = col / (col + vec3(0.72));
      col  = pow(clamp(col, 0.0, 1.0), vec3(0.90));

      // ── Atmospheric glow around the orb ─────────────────────────────────────
      float glowFalloff = exp(-rad * rad * 16.0 / (u_size * u_size));
      vec3  glowCol     = vec3(0.52, 0.18, 0.03) * u_brightness * 0.55;
      // Tint the glow too
      glowCol = mix(glowCol, glowCol * u_tint, u_tint_str * 0.3);

      // ── Atmospheric background warmth ────────────────────────────────────────
      // Subtle warm vignette centered on screen — replaces CSS body::before
      float vignette = exp(-rad * rad * 1.4);
      vec3 bgWarm = u_bg + vec3(0.018, 0.008, 0.001) * vignette;

      // ── Composite ────────────────────────────────────────────────────────────
      vec3 result = bgWarm;
      result      = mix(result, col, orb);
      result     += glowCol * glowFalloff * (1.0 - orb * 0.65) * 0.85;

      fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
    }
  `;

  onMount(() => {
    const gl = canvas.getContext('webgl2');
    if (!gl) { console.warn('WebGL2 not supported'); return; }

    function compile(type: number, src: string) {
      const s = gl!.createShader(type)!;
      gl!.shaderSource(s, src);
      gl!.compileShader(s);
      if (!gl!.getShaderParameter(s, gl!.COMPILE_STATUS))
        console.error('Shader error:', gl!.getShaderInfoLog(s));
      return s;
    }

    const prog = gl.createProgram()!;
    gl.attachShader(prog, compile(gl.VERTEX_SHADER, VS));
    gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FS));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      console.error('Program link error:', gl.getProgramInfoLog(prog));
      return;
    }
    gl.useProgram(prog);

    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);
    const posLoc = gl.getAttribLocation(prog, 'a_pos');
    gl.enableVertexAttribArray(posLoc);
    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);

    const U = {
      res:        gl.getUniformLocation(prog, 'iResolution'),
      time:       gl.getUniformLocation(prog, 'iTime'),
      speed:      gl.getUniformLocation(prog, 'u_speed'),
      brightness: gl.getUniformLocation(prog, 'u_brightness'),
      tint:       gl.getUniformLocation(prog, 'u_tint'),
      tintStr:    gl.getUniformLocation(prog, 'u_tint_str'),
      pulse:      gl.getUniformLocation(prog, 'u_pulse'),
      size:       gl.getUniformLocation(prog, 'u_size'),
      bg:         gl.getUniformLocation(prog, 'u_bg'),
      mouse:      gl.getUniformLocation(prog, 'u_mouse'),
      center:     gl.getUniformLocation(prog, 'u_center'),
      click:      gl.getUniformLocation(prog, 'u_click'),
      voice:      gl.getUniformLocation(prog, 'u_voice'),
    };

    // Cursor tracking — normalized [-1..1] relative to canvas center, Y flipped for GL
    let mouseTarget: [number, number]  = [0, 0];
    let mouseCurrent: [number, number] = [0, 0];
    let centerCurrent = 0;
    const onPointerMove = (e: PointerEvent) => {
      const rect = canvas.getBoundingClientRect();
      const nx = ((e.clientX - rect.left) / rect.width  - 0.5) * 2;
      const ny = -((e.clientY - rect.top)  / rect.height - 0.5) * 2;
      mouseTarget = [nx, ny];
    };
    const onPointerLeave = () => { mouseTarget = [0, 0]; };
    window.addEventListener('pointermove', onPointerMove);
    document.addEventListener('pointerleave', onPointerLeave);

    // Match warm bg colors from global.css
    const getBg = () =>
      document.documentElement.getAttribute('data-theme') === 'light'
        ? [0.984, 0.961, 0.910] as number[]   // #FBF5E8
        : [0.047, 0.035, 0.020] as number[];   // #0C0905

    let bgColor = getBg();
    const themeObserver = new MutationObserver(() => { bgColor = getBg(); });
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

    let cur = { ...PARAMS.idle, tint: [...PARAMS.idle.tint] as number[] };
    const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
    const start = performance.now();

    // FPS accumulator for debug overlay
    let fpsAcc = 0;
    let fpsFrames = 0;
    let fpsLast = start;

    let visible = true;
    const visObserver = new IntersectionObserver(
      entries => {
        const wasVisible = visible;
        visible = entries[0]?.isIntersecting ?? true;
        if (!wasVisible && visible && animFrame === 0) {
          animFrame = requestAnimationFrame(frame);
        }
      },
      { threshold: 0 },
    );
    visObserver.observe(canvas);

    function frame() {
      if (!visible) {
        animFrame = 0;
        return;
      }
      animFrame = requestAnimationFrame(frame);

      const tgt = PARAMS[_s];
      const k   = 0.022;
      cur.speed      = lerp(cur.speed,      tgt.speed,      k);
      cur.brightness = lerp(cur.brightness, tgt.brightness, k);
      cur.tintStr    = lerp(cur.tintStr,    tgt.tintStr,    k);
      cur.pulse      = lerp(cur.pulse,      tgt.pulse,      k);
      cur.size       = lerp(cur.size,       tgt.size,       k);
      for (let i = 0; i < 3; i++) cur.tint[i] = lerp(cur.tint[i], tgt.tint[i], k);

      const mk = 0.08;
      mouseCurrent[0] = lerp(mouseCurrent[0], mouseTarget[0], mk);
      mouseCurrent[1] = lerp(mouseCurrent[1], mouseTarget[1], mk);

      centerCurrent = lerp(centerCurrent, _cy, 0.05);

      const dpr = devicePixelRatio;
      const w   = Math.round(canvas.clientWidth  * dpr);
      const h   = Math.round(canvas.clientHeight * dpr);
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w; canvas.height = h;
        gl!.viewport(0, 0, w, h);
      }

      const now = performance.now();
      const t = (now - start) / 1000;
      const clickElapsed = clickAt === 0 ? 999 : (now - clickAt) / 1000;
      gl!.uniform2f(U.res, canvas.width, canvas.height);
      gl!.uniform1f(U.time,       t);
      gl!.uniform1f(U.speed,      cur.speed);
      gl!.uniform1f(U.brightness, cur.brightness);
      gl!.uniform3fv(U.tint,      cur.tint);
      gl!.uniform1f(U.tintStr,    cur.tintStr);
      gl!.uniform1f(U.pulse,      cur.pulse);
      gl!.uniform1f(U.size,       cur.size);
      gl!.uniform3fv(U.bg,        bgColor);
      gl!.uniform2f(U.mouse,      mouseCurrent[0], mouseCurrent[1]);
      gl!.uniform2f(U.center,     0, centerCurrent);
      gl!.uniform1f(U.click,      clickElapsed);
      gl!.uniform1f(U.voice,      _voice);
      gl!.drawArrays(gl!.TRIANGLE_STRIP, 0, 4);

      // Debug overlay — update at 4 Hz max
      fpsFrames++;
      if (now - fpsLast >= 250) {
        fpsAcc = Math.round((fpsFrames * 1000) / (now - fpsLast));
        fpsFrames = 0;
        fpsLast = now;
        fps = fpsAcc;
        dbg = {
          speed: +cur.speed.toFixed(2),
          brightness: +cur.brightness.toFixed(2),
          pulse: +cur.pulse.toFixed(2),
          size: +cur.size.toFixed(2),
          tint: cur.tint.map(x => x.toFixed(2)).join(', '),
        };
      }
    }

    frame();

    return () => {
      cancelAnimationFrame(animFrame);
      themeObserver.disconnect();
      visObserver.disconnect();
      window.removeEventListener('pointermove', onPointerMove);
      document.removeEventListener('pointerleave', onPointerLeave);
    };
  });
</script>

<div class="orb-root" style="--cy: {centerY}">
  <canvas bind:this={canvas}></canvas>
  {#if debug}
    <div class="debug-panel">
      <div class="debug-head">
        <div class="debug-title">DEBUG · orb</div>
        <button
          type="button"
          class="debug-close"
          aria-label="Выключить debug"
          title="Выключить debug"
          onclick={() => ondebugclose?.()}
        >×</button>
      </div>
      <div class="debug-row"><span>fps</span><span>{fps}</span></div>
      <div class="debug-row"><span>state</span><span>{orbState}</span></div>
      <div class="debug-row"><span>speed</span><span>{dbg.speed}</span></div>
      <div class="debug-row"><span>brightness</span><span>{dbg.brightness}</span></div>
      <div class="debug-row"><span>pulse</span><span>{dbg.pulse}</span></div>
      <div class="debug-row"><span>size</span><span>{dbg.size}</span></div>
      <div class="debug-row"><span>tint</span><span>{dbg.tint}</span></div>
    </div>
  {/if}
</div>

<style>
  .orb-root {
    position: relative;
    width: 100%;
    height: 100%;
  }

  canvas {
    width: 100%;
    height: 100%;
    display: block;
  }

  .debug-panel {
    position: absolute;
    top: 80px;
    right: 16px;
    min-width: 200px;
    padding: 10px 12px;
    background: color-mix(in srgb, var(--color-bg) 82%, transparent);
    backdrop-filter: blur(10px);
    -webkit-backdrop-filter: blur(10px);
    border: 1px solid var(--color-border);
    border-radius: 8px;
    font-family: var(--font-mono);
    font-size: 10px;
    letter-spacing: 0.04em;
    color: var(--color-text-dim);
    pointer-events: auto;
    z-index: 3;
    animation: fadeIn 0.3s ease;
  }

  .debug-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding-bottom: 6px;
    margin-bottom: 6px;
    border-bottom: 1px solid var(--color-border);
  }

  .debug-title {
    color: var(--color-accent);
    font-size: 9px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
  }

  .debug-close {
    width: 18px;
    height: 18px;
    border: none;
    background: transparent;
    color: var(--color-text-dim);
    font-family: var(--font-sans);
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
    border-radius: 4px;
    transition: color var(--transition), background var(--transition);
  }
  .debug-close:hover {
    color: var(--color-accent);
    background: var(--color-surface-hover);
  }

  .debug-row {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    padding: 2px 0;
  }

  .debug-row > span:last-child {
    color: var(--color-text);
  }

  @keyframes fadeIn {
    from { opacity: 0; transform: translateY(-4px); }
    to { opacity: 1; transform: translateY(0); }
  }
</style>
