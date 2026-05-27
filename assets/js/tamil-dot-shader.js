(function () {
  const canvases = document.querySelectorAll('canvas.tamil-dot-canvas');
  if (!canvases.length) return;

  const dpr = Math.min(window.devicePixelRatio || 1, 3);

  const vsSource = `
    attribute vec2 a_position;
    void main() {
      gl_Position = vec4(a_position, 0.0, 1.0);
    }
  `;

  // Neon bulb: solid bright core + soft exponential halo + tiny flicker.
  // No noise / no lava flow — just a glowing dot.
  const fsSource = `
    #ifdef GL_FRAGMENT_PRECISION_HIGH
      precision highp float;
    #else
      precision mediump float;
    #endif
    uniform float iTime;
    uniform vec2 iResolution;
    uniform vec3 uColor;

    float hash(float n) { return fract(sin(n) * 43758.5453); }

    void main() {
      vec2 uv = gl_FragCoord.xy / iResolution.xy;
      vec2 centered = uv - 0.5;
      float dist = length(centered);

      // Bulb tint follows the page text color (passed in from CSS).
      vec3 neonColor = uColor;

      // Bright core + soft halo, both in the text color.
      float core = exp(-dist * 22.0);
      float halo = exp(-dist * 6.0) * 0.85;

      vec3 color = neonColor;

      // --- Street-shop neon behaviour ---
      // Holds steady-lit for a random 1.0-1.5s, then breaks into a random
      // blink burst for the rest of the cycle, then repeats.
      float t = iTime;

      float cycleLen = 2.6;
      float cycleIdx = floor(t / cycleLen);
      float phase = mod(t, cycleLen);

      // Random steady-hold of 1.0-1.5s at the start of each cycle.
      float hold = 1.0 + 0.5 * hash(cycleIdx + 7.0);
      float inFit = step(hold, phase);   // 0 during hold, 1 during blink burst

      // Random on/off blink during the burst — slow enough that each on/off
      // reads as a distinct blink rather than a fast shimmer.
      float fast = step(0.55, hash(floor(t * 7.0) + 2.7));
      float onOff = mix(1.0, fast, inFit);

      // Gentle electrical buzz while it is lit.
      float buzz = 0.92 + 0.08 * hash(floor(t * 26.0) + 13.3);
      color *= buzz;

      // Premultiply by halo so the canvas edges fade out to transparent
      float alpha = clamp(halo + core, 0.0, 1.0) * onOff;
      gl_FragColor = vec4(color, alpha);
    }
  `;

  function runShader(canvas) {
    const glAttrs = { antialias: true, premultipliedAlpha: false };
    const gl = canvas.getContext('webgl', glAttrs) || canvas.getContext('experimental-webgl', glAttrs);
    if (!gl) return;

    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

    function resize() {
      const rect = canvas.getBoundingClientRect();
      const w = Math.max(1, Math.floor(rect.width * dpr));
      const h = Math.max(1, Math.floor(rect.height * dpr));
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
        gl.viewport(0, 0, canvas.width, canvas.height);
      }
    }
    resize();
    window.addEventListener('resize', resize);
    if (typeof ResizeObserver !== 'undefined') {
      new ResizeObserver(resize).observe(canvas);
    }
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(resize);
    }

    function compile(type, src) {
      const s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
        console.error(gl.getShaderInfoLog(s));
        return null;
      }
      return s;
    }

    const vs = compile(gl.VERTEX_SHADER, vsSource);
    const fs = compile(gl.FRAGMENT_SHADER, fsSource);
    const prog = gl.createProgram();
    gl.attachShader(prog, vs);
    gl.attachShader(prog, fs);
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      console.error(gl.getProgramInfoLog(prog));
      return;
    }
    gl.useProgram(prog);

    const verts = new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]);
    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, verts, gl.STATIC_DRAW);
    const aPos = gl.getAttribLocation(prog, 'a_position');
    gl.enableVertexAttribArray(aPos);
    gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);

    const uTime = gl.getUniformLocation(prog, 'iTime');
    const uRes = gl.getUniformLocation(prog, 'iResolution');
    const uColor = gl.getUniformLocation(prog, 'uColor');

    // Match the bulb to the page text color, re-read on theme (light/dark) change.
    let texColor = readTextColor(canvas);
    const scheme = window.matchMedia('(prefers-color-scheme: dark)');
    const onScheme = () => { texColor = readTextColor(canvas); };
    if (scheme.addEventListener) scheme.addEventListener('change', onScheme);

    function frame(t) {
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.uniform1f(uTime, t * 0.001);
      gl.uniform2f(uRes, canvas.width, canvas.height);
      gl.uniform3f(uColor, texColor[0], texColor[1], texColor[2]);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  // Read the inherited CSS text color of an element as normalized RGB (0..1).
  function readTextColor(el) {
    const c = getComputedStyle(el).color;
    const m = c.match(/[\d.]+/g);
    if (!m || m.length < 3) return [1.0, 1.0, 1.0];
    return [parseFloat(m[0]) / 255, parseFloat(m[1]) / 255, parseFloat(m[2]) / 255];
  }

  canvases.forEach(runShader);
})();
