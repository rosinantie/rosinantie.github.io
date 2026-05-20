(function () {
  const canvas = document.getElementById('hire-me-canvas');
  if (!canvas) return;
  const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
  if (!gl) return;

  const dpr = Math.min(window.devicePixelRatio || 1, 2);

  function resize() {
    const rect = canvas.getBoundingClientRect();
    canvas.width = Math.max(1, Math.floor(rect.width * dpr));
    canvas.height = Math.max(1, Math.floor(rect.height * dpr));
    gl.viewport(0, 0, canvas.width, canvas.height);
  }
  resize();
  window.addEventListener('resize', resize);

  const vsSource = `
    attribute vec2 a_position;
    void main() {
      gl_Position = vec4(a_position, 0.0, 1.0);
    }
  `;

  const fsSource = `
    precision mediump float;
    uniform float iTime;
    uniform vec2 iResolution;

    float rand(float x) {
      return fract(sin(x * 12.9898) * 43758.5453);
    }

    void main() {
      vec2 uv = gl_FragCoord.xy / iResolution.xy;

      // Hot-pink neon hue
      vec3 neon = vec3(1.0, 0.12, 0.65);

      // Slow breathing pulse
      float pulse = 0.75 + 0.25 * sin(iTime * 2.0);

      // Random per-tick flicker - mostly on, occasionally off
      float tick = floor(iTime * 14.0);
      float r = rand(tick);
      float flicker = step(0.12, r);
      // brief deep blink every few seconds
      float deepBlink = step(0.985, rand(floor(iTime * 1.7) + 17.0));
      flicker *= 1.0 - deepBlink;

      // Faint horizontal scanline jitter (tube hum)
      float hum = 0.93 + 0.07 * sin(uv.y * 80.0 + iTime * 40.0);

      // Inner-tube brightness - hotter in the middle of each letter row
      float core = 1.0 - abs(uv.y - 0.5) * 1.4;
      core = clamp(core, 0.0, 1.0);

      float bright = pulse * flicker * hum * (0.55 + 0.45 * core);

      vec3 color = neon * bright;
      // White-hot core highlight
      color += vec3(0.35) * pow(core, 5.0) * flicker;

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
