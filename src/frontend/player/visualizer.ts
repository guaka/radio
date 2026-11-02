  // Music visualization overlay — five WebGL shader visualizations driven by a
  // synthetic spectrum analyzer (bass/mid/treble + ~118 BPM beat). Toggled with
  // ⌘/Ctrl + V; arrows / 1–5 cycle visualizations while open; double-click the
  // viz area (not the mode bar) toggles browser fullscreen; Esc exits fullscreen
  // first, then closes the overlay. Lazy-initialized on first toggle and idle when hidden.
  (function () {
    const overlay  = document.getElementById('vizOverlay');
    const canvas   = document.getElementById('vizGl');
    const switcher = document.getElementById('vizSwitcher');
    const hint     = overlay && overlay.querySelector('.viz-hint');
    if (!overlay || !canvas || !switcher) return;
    const HINT_BASE = '↔ / 1–5 to switch · dbl-click fullscreen · esc to close';
    const HINT_LABEL = {
      live:        ' · LIVE',
      cors:        ' · SIM (CORS-tainted stream)',
      paused:      ' · paused',
      loading:     ' · SIM (audio loading)',
      'no-co':     ' · SIM (no crossorigin set)',
      'no-ctx':    ' · SIM (no AudioContext)',
      'ctx-suspended': ' · SIM (audio ctx suspended)',
      'no-audio':  ' · SIM (no audio element)',
      init:        ''
    };

    const NUM_BINS = 64;

    class SyntheticAnalyzer {
      constructor() {
        this.spectrum = new Float32Array(NUM_BINS);
        this.smoothed = new Float32Array(NUM_BINS);
        this.bass = 0; this.mid = 0; this.treble = 0; this.level = 0;
        this.beat = 0; this.beatPhase = 0; this.time = 0;
        this.t0 = performance.now();
        this.lastBeat = 0;
        this.bpm = 118;
        this._phaseOffset = Math.random() * 1000;
      }
      update() {
        const t = (performance.now() - this.t0) / 1000;
        this.bpm = 110 + 14 * Math.sin(t * 0.05);
        const beatPeriod = 60 / this.bpm;
        const beatT = (t % beatPeriod) / beatPeriod;
        const kick = Math.exp(-beatT * 6);
        const halfBeat = (t % (beatPeriod * 2)) / (beatPeriod * 2);
        const snare = halfBeat > 0.5 ? Math.exp(-(halfBeat - 0.5) * 12) * 0.7 : 0;
        const eighth = (t % (beatPeriod / 2)) / (beatPeriod / 2);
        const hat = Math.exp(-eighth * 10) * 0.4;
        const pad = 0.4 + 0.2 * Math.sin(t * 0.5) + 0.15 * Math.sin(t * 0.27);
        for (let i = 0; i < NUM_BINS; i++) {
          const f = i / NUM_BINS;
          let v = 0;
          if (f < 0.22) {
            const c = 1 - f / 0.22;
            v += kick * c * 1.1;
            v += pad * 0.25 * c;
          }
          if (f > 0.12 && f < 0.5) {
            const c = 1 - Math.abs(f - 0.28) / 0.2;
            v += snare * Math.max(0, c) * 0.9;
          }
          if (f > 0.2 && f < 0.7) {
            const c = 1 - Math.abs(f - 0.4) / 0.25;
            v += pad * 0.5 * Math.max(0, c);
            const melody = 0.4 * Math.max(0, Math.sin(t * 1.3 + i * 0.4));
            v += melody * Math.max(0, c) * 0.25;
          }
          if (f > 0.5) {
            const c = (f - 0.5) / 0.5;
            v += hat * (0.5 + c * 0.5) * 0.8;
            v += 0.12 * c * (0.5 + 0.5 * Math.sin(t * 7 + i));
          }
          v += 0.04 * ((Math.sin((i * 17.13 + t * 3.1 + this._phaseOffset) * 7.77) * 43758.5) % 1);
          v = Math.max(0, Math.min(1.3, v));
          this.spectrum[i] = v;
          this.smoothed[i] += (v - this.smoothed[i]) * 0.25;
        }
        let b = 0, m = 0, tr = 0;
        for (let i = 0; i < NUM_BINS; i++) {
          const v = this.smoothed[i];
          if (i < NUM_BINS * 0.2) b += v;
          else if (i < NUM_BINS * 0.55) m += v;
          else tr += v;
        }
        this.bass = b / (NUM_BINS * 0.2);
        this.mid = m / (NUM_BINS * 0.35);
        this.treble = tr / (NUM_BINS * 0.45);
        this.level = (this.bass + this.mid + this.treble) / 3;
        const sinceLastBeat = t - this.lastBeat;
        if (kick > 0.75 && sinceLastBeat > beatPeriod * 0.8) {
          this.beat = 1;
          this.lastBeat = t;
        } else {
          this.beat *= 0.9;
        }
        this.beatPhase = beatT;
        this.time = t;
      }
    }

    class RealAnalyzer {
      constructor() {
        this.spectrum = new Float32Array(NUM_BINS);
        this.smoothed = new Float32Array(NUM_BINS);
        this.bass = 0; this.mid = 0; this.treble = 0; this.level = 0;
        this.beat = 0; this.beatPhase = 0; this.time = 0;
        this.t0 = performance.now();
        this.synth = new SyntheticAnalyzer();
        this.audio = document.getElementById('audio');
        this.ctx = null;
        this.analyser = null;
        this.bytes = null;
        this.lastNonZero = 0;
        this.bassHistory = new Float32Array(43);
        this.bassIdx = 0;
        this.lastBeatTime = 0;
        this.mode = 'init';
        this._tryConnect();
      }
      _tryConnect() {
        if (!this.audio) return;
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return;
        try {
          if (this.audio._guakaVizAnalyser && this.audio._guakaVizCtx) {
            this.ctx = this.audio._guakaVizCtx;
            this.analyser = this.audio._guakaVizAnalyser;
          } else {
            this.ctx = new Ctx();
            const src = this.ctx.createMediaElementSource(this.audio);
            const an = this.ctx.createAnalyser();
            an.fftSize = 1024;
            an.smoothingTimeConstant = 0.7;
            src.connect(an);
            an.connect(this.ctx.destination);
            this.audio._guakaVizCtx = this.ctx;
            this.audio._guakaVizAnalyser = an;
            this.audio._guakaVizSrc = src;
            this.analyser = an;
          }
          this.bytes = new Uint8Array(this.analyser.frequencyBinCount);
        } catch (e) {
          this.ctx = null;
          this.analyser = null;
          this.bytes = null;
        }
      }
      resume() {
        if (this.ctx && this.ctx.state === 'suspended') {
          this.ctx.resume().catch(() => {});
        }
      }
      update() {
        const now = performance.now();
        this.time = (now - this.t0) / 1000;

        let realOk = false;
        let reason = 'no-audio';
        if (!this.audio) reason = 'no-audio';
        else if (!this.ctx) reason = 'no-ctx';
        else if (this.ctx.state === 'suspended') reason = 'ctx-suspended';
        else if (this.audio.paused) reason = 'paused';
        else if (this.audio.readyState < 2) reason = 'loading';
        else if (!this.audio.crossOrigin) reason = 'no-co';
        else if (this.analyser) {
          this.analyser.getByteFrequencyData(this.bytes);
          let sum = 0;
          const N = this.bytes.length;
          for (let i = 0; i < N; i++) sum += this.bytes[i];
          if (sum > 0) { this.lastNonZero = now; realOk = true; }
          else if (now - this.lastNonZero < 600) realOk = true;
          else reason = 'cors';
        }

        if (!realOk) {
          this.synth.update();
          for (let i = 0; i < NUM_BINS; i++) {
            this.spectrum[i] = this.synth.spectrum[i];
            this.smoothed[i] = this.synth.smoothed[i];
          }
          const paused = (reason === 'paused');
          const dim = paused ? 0.35 : 1.0;
          this.bass = this.synth.bass * dim;
          this.mid = this.synth.mid * dim;
          this.treble = this.synth.treble * dim;
          this.level = this.synth.level * dim;
          this.beat = this.synth.beat * dim;
          this.beatPhase = this.synth.beatPhase;
          if (this.mode !== reason) {
            this.mode = reason;
            try {
              console.warn('[viz] fallback ->', reason, {
                crossOrigin: this.audio && this.audio.crossOrigin,
                currentSrc: this.audio && this.audio.currentSrc,
                paused: this.audio && this.audio.paused,
                readyState: this.audio && this.audio.readyState,
                ctxState: this.ctx && this.ctx.state,
                fftBins: this.bytes && this.bytes.length
              });
            } catch (_) {}
          }
          return;
        }
        if (this.mode !== 'live') {
          this.mode = 'live';
          try { console.info('[viz] LIVE — reading real audio'); } catch (_) {}
        }

        const bytesN = this.bytes.length;
        for (let i = 0; i < NUM_BINS; i++) {
          const lo = Math.floor(Math.pow(i / NUM_BINS, 1.7) * bytesN);
          const hi = Math.max(lo + 1, Math.floor(Math.pow((i + 1) / NUM_BINS, 1.7) * bytesN));
          let s = 0, c = 0;
          for (let j = lo; j < hi && j < bytesN; j++) { s += this.bytes[j]; c++; }
          const v = c ? (s / c) / 255 * 1.3 : 0;
          this.spectrum[i] = v;
          this.smoothed[i] += (v - this.smoothed[i]) * 0.3;
        }
        let b = 0, m = 0, tr = 0;
        for (let i = 0; i < NUM_BINS; i++) {
          const v = this.smoothed[i];
          if (i < NUM_BINS * 0.2) b += v;
          else if (i < NUM_BINS * 0.55) m += v;
          else tr += v;
        }
        this.bass = b / (NUM_BINS * 0.2);
        this.mid = m / (NUM_BINS * 0.35);
        this.treble = tr / (NUM_BINS * 0.45);
        this.level = (this.bass + this.mid + this.treble) / 3;

        let avg = 0;
        for (let i = 0; i < this.bassHistory.length; i++) avg += this.bassHistory[i];
        avg /= this.bassHistory.length;
        this.bassHistory[this.bassIdx] = this.bass;
        this.bassIdx = (this.bassIdx + 1) % this.bassHistory.length;
        const sinceBeat = (now - this.lastBeatTime) / 1000;
        if (this.bass > Math.max(0.22, avg * 1.35) && sinceBeat > 0.22) {
          this.beat = 1;
          this.lastBeatTime = now;
        } else {
          this.beat *= 0.9;
        }
        this.beatPhase = Math.min(1, sinceBeat * 2);
      }
    }

    const VERT = `
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
`;

    const UNIFORMS_HEADER = `
precision highp float;
uniform float uTime;
uniform vec2  uRes;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
uniform float uLevel;
uniform float uBeat;
uniform float uBeatPhase;
uniform float uSpectrum[64];

float spec(float f) {
  f = clamp(f, 0.0, 1.0) * 63.0;
  int i = int(floor(f));
  float v = 0.0;
  for (int k = 0; k < 64; k++) {
    if (k == i) v = uSpectrum[k];
  }
  return v;
}

float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1,311.7)))*43758.5453); }
float noise(vec2 p){
  vec2 i = floor(p), f = fract(p);
  float a = hash(i);
  float b = hash(i+vec2(1.,0.));
  float c = hash(i+vec2(0.,1.));
  float d = hash(i+vec2(1.,1.));
  vec2 u = f*f*(3.0-2.0*f);
  return mix(mix(a,b,u.x), mix(c,d,u.x), u.y);
}
float fbm(vec2 p){
  float v = 0.0, a = 0.5;
  for (int i = 0; i < 5; i++) { v += a*noise(p); p *= 2.02; a *= 0.5; }
  return v;
}
mat2 rot(float a){ float c=cos(a), s=sin(a); return mat2(c,-s,s,c); }
`;

    const FRAG_FUTURIST = UNIFORMS_HEADER + `
void main(){
  vec2 uv = (gl_FragCoord.xy - 0.5*uRes) / uRes.y;
  vec3 col = vec3(0.02, 0.01, 0.04);
  float sunY = -0.05 + uBass * 0.03;
  vec2 sp = uv - vec2(0.0, sunY);
  float sunR = 0.32 + uLevel*0.02;
  float sd = length(sp) - sunR;
  float sunMask = smoothstep(0.004, -0.004, sd);
  float gradient = 1.0 - clamp((sp.y + sunR)/(sunR*2.0), 0.0, 1.0);
  vec3 sunCol = mix(vec3(1.0, 0.85, 0.2), vec3(1.0, 0.15, 0.45), gradient);
  float bars = step(0.0, sin((sp.y + sunY)*40.0 - uTime*1.5 + uBeat*2.0));
  float barMask = mix(1.0, step(sp.y, -0.03 + 0.3*sin(uTime*0.3)), bars);
  col = mix(col, sunCol, sunMask * (0.6 + 0.4*barMask));
  col += sunCol * exp(-max(sd,0.0)*8.0) * 0.35;
  if (uv.y < 0.0) {
    vec2 g = uv;
    float persp = 1.0 / max(-g.y, 0.002);
    vec2 gp = vec2(g.x * persp, persp + uTime * (0.6 + uLevel*1.5));
    vec2 gid = abs(fract(gp) - 0.5);
    float line = min(gid.x, gid.y);
    float lineW = 0.03 + 0.015*uBass;
    float gridMask = smoothstep(lineW, 0.0, line);
    gridMask *= smoothstep(0.0, 0.6, -g.y);
    vec3 gridCol = mix(vec3(0.9, 0.1, 0.6), vec3(0.1, 0.8, 1.0), smoothstep(0.0,0.4,-g.y));
    col += gridCol * gridMask * (1.5 + uBeat*0.8);
    col += vec3(0.8, 0.2, 0.5) * exp(g.y * 20.0) * 0.2;
  }
  if (uv.y > -0.02) {
    vec2 sid = floor(uv * vec2(200.0, 120.0));
    float st = hash(sid);
    if (st > 0.985) {
      float tw = 0.5 + 0.5*sin(uTime*3.0 + st*40.0);
      col += vec3(tw) * 0.6;
    }
  }
  for (int i = 0; i < 32; i++) {
    float fi = float(i)/32.0;
    float x = (fi - 0.5) * 1.6;
    float s = uSpectrum[i*2];
    float h = 0.18 * s;
    float bar = smoothstep(0.008, 0.0, abs(uv.x - x)) * step(uv.y, -0.38) * step(-0.38 - h, uv.y);
    vec3 bc = mix(vec3(0.1, 0.9, 1.0), vec3(1.0, 0.2, 0.8), fi);
    col += bc * bar * 1.2;
  }
  col *= 0.9 + 0.1*sin(gl_FragCoord.y*3.0);
  col *= 1.0 - 0.5*length(uv*vec2(0.7,1.0));
  gl_FragColor = vec4(col, 1.0);
}
`;

    const FRAG_PSYCHEDELIC = UNIFORMS_HEADER + `
vec3 hsv2rgb(vec3 c){
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz)*6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p-K.xxx, 0.0, 1.0), c.y);
}
void main(){
  vec2 uv = (gl_FragCoord.xy - 0.5*uRes) / uRes.y;
  float segs = 6.0 + floor(uTreble*4.0);
  float a = atan(uv.y, uv.x);
  float r = length(uv);
  a = mod(a, 6.2831853/segs);
  a = abs(a - 3.1415926/segs);
  vec2 p = vec2(cos(a), sin(a)) * r;
  p = rot(uTime*0.15 + uBeat*0.4) * p;
  vec2 q = p;
  float v = 0.0;
  float amp = 1.0;
  for (int i = 0; i < 5; i++) {
    q = abs(q)/dot(q,q) - vec2(0.8 + 0.2*sin(uTime*0.3), 0.9);
    q = rot(0.3 + uBass*0.2) * q;
    v += amp * exp(-length(q)*1.2);
    amp *= 0.7;
  }
  float hue = fract(uTime*0.07 + v*0.3 + r*0.5);
  float sat = 0.75 + 0.25*uBeat;
  float val = clamp(v * (0.5 + uLevel*1.2), 0.0, 1.5);
  vec3 col = hsv2rgb(vec3(hue, sat, val));
  float ringBand = spec(fract(r*0.7 + uTime*0.1));
  col += hsv2rgb(vec3(fract(hue+0.4), 0.9, 1.0)) * ringBand * 0.5;
  col += hsv2rgb(vec3(fract(hue+0.5), 0.6, 1.0)) * exp(-length(uv)*3.0) * uBass * 0.6;
  col *= 1.0 - 0.3*length(uv);
  gl_FragColor = vec4(col, 1.0);
}
`;

    const FRAG_LIQUID = UNIFORMS_HEADER + `
float smin(float a, float b, float k){
  float h = clamp(0.5 + 0.5*(b-a)/k, 0.0, 1.0);
  return mix(b, a, h) - k*h*(1.0-h);
}
float map(vec3 p){
  float d = 1e5;
  for (int i = 0; i < 6; i++) {
    float fi = float(i);
    vec3 c = vec3(
      sin(uTime*0.7 + fi*1.3) * (1.2 + uMid*0.3),
      cos(uTime*0.5 + fi*2.1) * (1.0 + uBass*0.4),
      sin(uTime*0.4 + fi*0.7) * 1.2
    );
    float r = 0.45 + 0.15*sin(uTime + fi) + uLevel*0.15;
    d = smin(d, length(p - c) - r, 0.6);
  }
  return d;
}
vec3 calcNormal(vec3 p){
  vec2 e = vec2(0.001, 0.0);
  return normalize(vec3(
    map(p+e.xyy) - map(p-e.xyy),
    map(p+e.yxy) - map(p-e.yxy),
    map(p+e.yyx) - map(p-e.yyx)
  ));
}
vec3 iridescence(float t){
  return 0.5 + 0.5*cos(6.2831*(vec3(0.0,0.33,0.67) + t));
}
void main(){
  vec2 uv = (gl_FragCoord.xy - 0.5*uRes) / uRes.y;
  vec3 ro = vec3(0.0, 0.0, -4.0);
  vec3 rd = normalize(vec3(uv, 1.4));
  float t = 0.0;
  float hit = 0.0;
  vec3 p;
  for (int i = 0; i < 64; i++) {
    p = ro + rd*t;
    float d = map(p);
    if (d < 0.002) { hit = 1.0; break; }
    if (t > 10.0) break;
    t += d * 0.85;
  }
  vec3 col = vec3(0.02, 0.025, 0.04);
  col += vec3(0.1, 0.05, 0.15) * (0.5 + 0.5*uv.y);
  col += step(0.995, hash(floor(uv*400.0))) * 0.7;
  if (hit > 0.5) {
    vec3 n = calcNormal(p);
    vec3 v = -rd;
    float fres = pow(1.0 - max(dot(n, v), 0.0), 3.0);
    vec3 r = reflect(rd, n);
    float envy = r.y*0.5+0.5;
    vec3 env = mix(vec3(0.9, 0.2, 0.6), vec3(0.1, 0.7, 1.0), envy);
    env = mix(env, vec3(1.0, 0.9, 0.3), smoothstep(0.6, 1.0, envy));
    vec3 irid = iridescence(dot(n, v) + uTime*0.1 + uBeat*0.3);
    vec3 surf = env * 0.6 + irid * 0.7;
    surf += fres * vec3(1.0, 0.9, 1.2) * (1.0 + uLevel);
    col = surf;
    col *= exp(-t*0.05);
  }
  col = pow(col, vec3(0.85));
  gl_FragColor = vec4(col, 1.0);
}
`;

    const FRAG_PLASMA = UNIFORMS_HEADER + `
void main(){
  vec2 uv = (gl_FragCoord.xy - 0.5*uRes) / uRes.y;
  vec2 p = uv * 1.8;
  p += vec2(sin(uTime*0.3), cos(uTime*0.2)) * 0.4;
  float n = 0.0;
  vec2 q = p;
  float amp = 0.6;
  for (int i = 0; i < 5; i++) {
    q = rot(0.5 + uMid*0.3) * q;
    n += amp * sin(q.x*2.0 + uTime*0.7) * cos(q.y*2.0 - uTime*0.5);
    q *= 1.8;
    amp *= 0.55;
  }
  n = n*0.5 + 0.5;
  float f = fbm(p*2.0 + n + uTime*0.2);
  float cloud = smoothstep(0.3, 0.9, f + uLevel*0.3);
  vec3 c1 = vec3(0.05, 0.0, 0.15);
  vec3 c2 = vec3(0.6, 0.1, 0.9);
  vec3 c3 = vec3(1.0, 0.2, 0.6);
  vec3 c4 = vec3(0.2, 0.9, 1.0);
  vec3 col = mix(c1, c2, smoothstep(0.0, 0.4, cloud));
  col = mix(col, c3, smoothstep(0.35, 0.7, cloud));
  col = mix(col, c4, smoothstep(0.75, 1.0, cloud + uBeat*0.2));
  float arcSeed = floor(uTime*3.0 + uBeat*5.0);
  vec2 arcDir = vec2(cos(arcSeed*2.3), sin(arcSeed*1.7));
  float arc = abs(dot(uv - vec2(hash(vec2(arcSeed,1.0))-0.5, hash(vec2(arcSeed,2.0))-0.5), arcDir));
  float jitter = fbm(uv*8.0 + arcSeed)*0.08;
  float arcMask = smoothstep(0.004 + jitter, 0.0, arc) * smoothstep(0.6, 0.0, length(uv));
  col += vec3(0.8, 0.9, 1.0) * arcMask * (0.3 + uBeat*1.5);
  float core = exp(-length(uv)*2.5) * uBass;
  col += vec3(1.0, 0.7, 0.9) * core * 0.6;
  vec2 sp = uv*vec2(80.0, 80.0);
  vec2 spId = floor(sp);
  vec2 spFr = fract(sp) - 0.5;
  float sparkSeed = hash(spId + floor(uTime*2.0));
  float spark = step(0.995, sparkSeed) * smoothstep(0.2, 0.0, length(spFr));
  col += vec3(1.0, 0.95, 0.8) * spark * uTreble * 1.5;
  gl_FragColor = vec4(col, 1.0);
}
`;

    const FRAG_COSMIC = UNIFORMS_HEADER + `
vec3 hsv2rgb2(vec3 c){
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz)*6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p-K.xxx, 0.0, 1.0), c.y);
}
void main(){
  vec2 uv = (gl_FragCoord.xy - 0.5*uRes) / uRes.y;
  float r = length(uv);
  float a = atan(uv.y, uv.x);
  vec3 col = vec3(0.005, 0.008, 0.02);
  for (int k = 0; k < 3; k++) {
    float fk = float(k);
    vec2 grid = floor(uv * (80.0 + fk*60.0));
    float h = hash(grid + fk*31.0);
    if (h > 0.992 - fk*0.003) {
      vec2 cp = fract(uv*(80.0+fk*60.0)) - 0.5;
      float s = smoothstep(0.1, 0.0, length(cp));
      float tw = 0.5 + 0.5*sin(uTime*(1.0+h*4.0) + h*20.0);
      col += vec3(tw) * s * (0.3 + fk*0.15);
    }
  }
  vec2 np = uv*1.2 + vec2(uTime*0.02, uTime*0.01);
  float neb = fbm(np*1.5);
  neb += 0.5*fbm(np*4.0 + neb);
  vec3 nebCol = hsv2rgb2(vec3(fract(0.6 + neb*0.3 + uTime*0.03), 0.7, 1.0));
  col += nebCol * smoothstep(0.35, 0.9, neb) * 0.5;
  vec3 neb2 = hsv2rgb2(vec3(fract(0.02 + neb*0.2), 0.85, 1.0));
  col += neb2 * smoothstep(0.55, 0.85, fbm(np*2.3 - uTime*0.05)) * 0.3;
  float ringR = 0.42;
  float ringThick = 0.25;
  float ringDist = r - ringR;
  if (ringDist > -ringThick*0.2 && ringDist < ringThick) {
    float idx = mod(a + uTime*0.15, 6.2831853) / 6.2831853;
    float s = spec(idx);
    float barLen = s * 0.35;
    float inBar = smoothstep(barLen, barLen - 0.01, ringDist) * step(-0.005, ringDist);
    float tile = fract(idx * 64.0);
    float sep = smoothstep(0.02, 0.08, tile) * smoothstep(0.02, 0.08, 1.0-tile);
    vec3 bc = hsv2rgb2(vec3(fract(idx + uTime*0.1), 0.8, 1.0));
    col += bc * inBar * sep * (1.2 + uBeat*0.6);
  }
  float ringGlow = exp(-abs(r - ringR)*10.0) * (0.6 + uBass*0.8);
  col += hsv2rgb2(vec3(fract(uTime*0.05), 0.5, 1.0)) * ringGlow * 0.3;
  float core = exp(-r*6.0) * (0.6 + uBeat*1.0);
  col += vec3(1.0, 0.95, 1.2) * core;
  float rays = pow(abs(sin(a*5.0 + uTime*0.4)), 20.0) * exp(-r*2.5) * uLevel;
  col += vec3(0.8, 0.6, 1.0) * rays * 0.5;
  for (int i = 0; i < 8; i++) {
    float fi = float(i);
    float orbit = 0.6 + 0.08*fi + 0.05*sin(uTime*0.3 + fi);
    float speed = 0.2 + fi*0.05;
    vec2 pp = vec2(cos(uTime*speed + fi*1.7), sin(uTime*speed + fi*1.7)) * orbit;
    float d = length(uv - pp);
    float glow = exp(-d*40.0);
    vec3 pc = hsv2rgb2(vec3(fract(fi*0.13 + uTime*0.1), 0.6, 1.0));
    col += pc * glow * (0.8 + uBeat*0.5);
  }
  gl_FragColor = vec4(col, 1.0);
}
`;

    const SHADERS = [
      { id: 'futurist',    name: 'Futurist',        frag: FRAG_FUTURIST },
      { id: 'psychedelic', name: 'Psychedelic',     frag: FRAG_PSYCHEDELIC },
      { id: 'liquid',      name: 'Liquid Metal',    frag: FRAG_LIQUID },
      { id: 'plasma',      name: 'Plasma Storm',    frag: FRAG_PLASMA },
      { id: 'cosmic',      name: 'Cosmic Spectrum', frag: FRAG_COSMIC },
    ];

    let analyzer = null;
    let gl = null;
    let programs = [];
    let bufVtx = null;
    let currentIdx = 0;
    let rafId = 0;
    let initFailed = false;

    function vizFullscreenElement() {
      const el = document.fullscreenElement || document.webkitFullscreenElement;
      return el === overlay ? el : null;
    }
    function isVizFullscreen() {
      return !!vizFullscreenElement();
    }
    function requestVizFullscreen() {
      if (!overlay || isVizFullscreen()) return Promise.resolve();
      if (overlay.requestFullscreen) return overlay.requestFullscreen();
      if (overlay.webkitRequestFullscreen) {
        try {
          overlay.webkitRequestFullscreen();
        } catch (e) {
          return Promise.reject(e);
        }
        return Promise.resolve();
      }
      return Promise.reject(new Error('fullscreen unavailable'));
    }
    function exitVizFullscreen() {
      if (!isVizFullscreen()) return Promise.resolve();
      if (document.exitFullscreen) return document.exitFullscreen();
      if (document.webkitExitFullscreen) {
        try {
          document.webkitExitFullscreen();
        } catch (e) {
          return Promise.reject(e);
        }
        return Promise.resolve();
      }
      return Promise.resolve();
    }
    function toggleVizFullscreen() {
      if (isVizFullscreen()) return exitVizFullscreen();
      return requestVizFullscreen();
    }

    function compile(type, src) {
      const sh = gl.createShader(type);
      gl.shaderSource(sh, src);
      gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
        const log = gl.getShaderInfoLog(sh);
        console.error('viz shader error:', log);
        throw new Error(log);
      }
      return sh;
    }

    function link(vs, fs) {
      const p = gl.createProgram();
      gl.attachShader(p, vs);
      gl.attachShader(p, fs);
      gl.bindAttribLocation(p, 0, 'aPos');
      gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
        const log = gl.getProgramInfoLog(p);
        console.error('viz link error:', log);
        throw new Error(log);
      }
      return p;
    }

    function buildSwitcher() {
      SHADERS.forEach((v, i) => {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'viz-btn';
        b.dataset.vizBtn = '';
        b.innerHTML = '<span class="num">0' + (i + 1) + '</span><span class="nm">' + v.name + '</span>';
        b.addEventListener('click', () => setViz(i));
        switcher.appendChild(b);
      });
    }

    function initRenderer() {
      if (gl || initFailed) return !!gl;
      try {
        gl = canvas.getContext('webgl', { antialias: false, alpha: false, premultipliedAlpha: false });
      } catch (e) { gl = null; }
      if (!gl) {
        initFailed = true;
        switcher.innerHTML = '<div style="color:rgba(255,255,255,0.5);padding:8px 14px;font-size:11px;">WebGL unavailable</div>';
        return false;
      }
      analyzer = new RealAnalyzer();
      const vs = compile(gl.VERTEX_SHADER, VERT);
      bufVtx = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, bufVtx);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
        -1, -1,  1, -1,  -1, 1,
        -1,  1,  1, -1,   1, 1
      ]), gl.STATIC_DRAW);
      programs = SHADERS.map(def => {
        const fs = compile(gl.FRAGMENT_SHADER, def.frag);
        const prog = link(vs, fs);
        return {
          def, prog,
          u: {
            uTime:      gl.getUniformLocation(prog, 'uTime'),
            uRes:       gl.getUniformLocation(prog, 'uRes'),
            uBass:      gl.getUniformLocation(prog, 'uBass'),
            uMid:       gl.getUniformLocation(prog, 'uMid'),
            uTreble:    gl.getUniformLocation(prog, 'uTreble'),
            uLevel:     gl.getUniformLocation(prog, 'uLevel'),
            uBeat:      gl.getUniformLocation(prog, 'uBeat'),
            uBeatPhase: gl.getUniformLocation(prog, 'uBeatPhase'),
            uSpectrum:  gl.getUniformLocation(prog, 'uSpectrum[0]'),
          }
        };
      });
      buildSwitcher();
      let stored = parseInt(localStorage.getItem('guakaVizIdx') || '0', 10);
      if (isNaN(stored) || stored < 0 || stored >= programs.length) stored = 0;
      setViz(stored);
      return true;
    }

    function resize() {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const w = Math.max(1, Math.floor(canvas.clientWidth * dpr));
      const h = Math.max(1, Math.floor(canvas.clientHeight * dpr));
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
      }
      gl.viewport(0, 0, canvas.width, canvas.height);
    }

    function setViz(idx) {
      currentIdx = idx;
      try { localStorage.setItem('guakaVizIdx', String(idx)); } catch (e) {}
      const buttons = switcher.querySelectorAll('[data-viz-btn]');
      buttons.forEach((el, i) => el.classList.toggle('active', i === idx));
    }

    function frame() {
      if (!overlay.classList.contains('active')) {
        rafId = 0;
        return;
      }
      resize();
      analyzer.update();
      const A = analyzer;
      const p = programs[currentIdx];
      gl.useProgram(p.prog);
      gl.bindBuffer(gl.ARRAY_BUFFER, bufVtx);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.uniform1f(p.u.uTime, A.time);
      gl.uniform2f(p.u.uRes, canvas.width, canvas.height);
      gl.uniform1f(p.u.uBass, A.bass);
      gl.uniform1f(p.u.uMid, A.mid);
      gl.uniform1f(p.u.uTreble, A.treble);
      gl.uniform1f(p.u.uLevel, A.level);
      gl.uniform1f(p.u.uBeat, A.beat);
      gl.uniform1f(p.u.uBeatPhase, A.beatPhase);
      gl.uniform1fv(p.u.uSpectrum, A.smoothed);
      gl.drawArrays(gl.TRIANGLES, 0, 6);
      if (hint) {
        const label = HINT_LABEL[A.mode] || '';
        const next = HINT_BASE + label;
        if (hint.textContent !== next) hint.textContent = next;
      }
      rafId = requestAnimationFrame(frame);
    }

    function isVizActive() {
      return overlay.classList.contains('active');
    }

    function openViz() {
      if (!initRenderer()) return;
      if (analyzer && analyzer.resume) analyzer.resume();
      overlay.classList.add('active');
      overlay.setAttribute('aria-hidden', 'false');
      if (!rafId) rafId = requestAnimationFrame(frame);
    }

    function closeViz() {
      return exitVizFullscreen()
        .catch(() => {})
        .then(() => {
          overlay.classList.remove('active');
          overlay.setAttribute('aria-hidden', 'true');
        });
    }

    function toggleViz() {
      if (isVizActive()) void closeViz();
      else openViz();
    }

    window.addEventListener('keydown', (e) => {
      const ae = document.activeElement;
      const inField = ae && (
        ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA' ||
        ae.tagName === 'SELECT' || ae.isContentEditable
      );

      if ((e.metaKey || e.ctrlKey) && (e.key === 'v' || e.key === 'V')) {
        if (inField) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        toggleViz();
        return;
      }

      if (!isVizActive()) return;

      if (e.key === 'Escape') {
        const settingsEl = document.getElementById('settingsModal');
        if (settingsEl && settingsEl.classList.contains('open')) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        if (isVizFullscreen()) {
          void exitVizFullscreen().catch(() => {});
          return;
        }
        void closeViz();
        return;
      }

      if (inField) return;

      if (e.key >= '1' && e.key <= '5') {
        const i = parseInt(e.key, 10) - 1;
        if (programs[i]) {
          e.preventDefault();
          e.stopImmediatePropagation();
          setViz(i);
        }
        return;
      }
      if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        e.preventDefault();
        e.stopImmediatePropagation();
        const dir = e.key === 'ArrowRight' ? 1 : -1;
        setViz((currentIdx + dir + programs.length) % programs.length);
      }
    }, true);

    window.addEventListener('resize', () => {
      if (gl && isVizActive()) resize();
    });

    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && isVizActive() && !rafId) {
        rafId = requestAnimationFrame(frame);
      }
    });

    function onVizFullscreenChange() {
      if (gl && isVizActive()) resize();
    }
    document.addEventListener('fullscreenchange', onVizFullscreenChange);
    document.addEventListener('webkitfullscreenchange', onVizFullscreenChange);

    overlay.addEventListener('dblclick', (e) => {
      if (!isVizActive() || initFailed) return;
      if (switcher.contains(e.target)) return;
      e.preventDefault();
      void toggleVizFullscreen().catch(() => {});
    });
  })();
  
