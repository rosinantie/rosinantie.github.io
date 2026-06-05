(function () {
  const canvas = document.getElementById('hire-me-canvas');
  if (!canvas) return;
  const glAttrs = { antialias: true, premultipliedAlpha: false };
  const gl = canvas.getContext('webgl', glAttrs) || canvas.getContext('experimental-webgl', glAttrs);
  if (!gl) return;

  const dpr = Math.min(window.devicePixelRatio || 1, 3);

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

  const vsSource = `
    attribute vec2 a_position;
    void main() {
      gl_Position = vec4(a_position, 0.0, 1.0);
    }
  `;

  const fsSource = `
    #ifdef GL_FRAGMENT_PRECISION_HIGH
      precision highp float;
    #else
      precision mediump float;
    #endif
    uniform float iTime;
    uniform vec2 iResolution;

    vec3 hsv2rgb(vec3 c) {
      vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
      vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
      return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }

    // 2D value noise + fbm for that flowing lava feel
    float hash(vec2 p) {
      return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
    }

    float vnoise(vec2 p) {
      vec2 i = floor(p);
      vec2 f = fract(p);
      vec2 u = f * f * (3.0 - 2.0 * f);
      float a = hash(i);
      float b = hash(i + vec2(1.0, 0.0));
      float c = hash(i + vec2(0.0, 1.0));
      float d = hash(i + vec2(1.0, 1.0));
      return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
    }

    float fbm(vec2 p) {
      float v = 0.0;
      float a = 0.5;
      for (int i = 0; i < 5; i++) {
        v += a * vnoise(p);
        p *= 2.02;
        a *= 0.5;
      }
      return v;
    }

    void main() {
      // Stretch coords so the flow reads nicely inside the wide "hire me" mask
      vec2 uv = gl_FragCoord.xy / iResolution.xy;
      vec2 p = vec2(uv.x * 3.0, uv.y);

      // Domain-warped fbm - molten currents drifting sideways
      vec2 flow = vec2(iTime * 0.18, iTime * 0.05);
      vec2 q = vec2(
        fbm(p * 2.5 + flow),
        fbm(p * 2.5 + flow + vec2(5.2, 1.3))
      );
      vec2 r = vec2(
        fbm(p * 2.5 + 1.7 * q + vec2(1.7, 9.2) + iTime * 0.12),
        fbm(p * 2.5 + 1.7 * q + vec2(8.3, 2.8) - iTime * 0.10)
      );
      float n = fbm(p * 2.5 + 2.0 * r);

      // Drive hue by the noise so colors slosh through the letters
      float hue = fract(0.62 + n * 0.55 + iTime * 0.04);
      float sat = 0.85 - 0.35 * n;
      float val = 0.55 + 0.85 * n;
      vec3 color = hsv2rgb(vec3(hue, sat, val));

      // Bright veins where the flow piles up - molten highlights
      float veins = smoothstep(0.55, 0.95, n);
      color += vec3(1.0, 0.85, 0.6) * pow(veins, 3.0) * 0.6;

      // Gentle breathing so the whole thing feels alive
      float pulse = 0.9 + 0.1 * sin(iTime * 1.3);
      color *= pulse;

      gl_FragColor = vec4(color, 1.0);
    }
  `;

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

  function frame(t) {
    gl.uniform1f(uTime, t * 0.001);
    gl.uniform2f(uRes, canvas.width, canvas.height);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
})();
