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

  // PewDiePie brand bars: smooth red/blue diagonal bands rippling and
  // scrolling across the word. Tuned to read cleanly in the wide, short mask.
  const fsSource = `
    precision mediump float;
    uniform float iTime;
    uniform vec2 iResolution;

    const float PI = 3.14159265359;

    void main() {
      vec2 uv = gl_FragCoord.xy / iResolution.xy;

      // Diagonal stripe coordinate. The mask is wide and short, so weight y
      // heavily to keep the bands at a lively diagonal rather than near-flat.
      float aspect = iResolution.x / iResolution.y;
      float d = uv.x * aspect + uv.y * 3.0;

      // Undulate the bands so they ripple like a wave.
      d += sin(uv.x * aspect * 2.0 - iTime * 1.5) * 0.30;
      d += sin(uv.y * 6.2831 + iTime * 1.1) * 0.12;

      // Scroll the pattern across the word.
      float bands = d * 1.5 - iTime * 1.3;

      // Smooth alternation between the two brand colours.
      float t = 0.5 + 0.5 * sin(bands * PI);
      float band = smoothstep(0.32, 0.68, t);

      vec3 red  = vec3(228.0 / 255.0, 18.0 / 255.0, 55.0 / 255.0);
      vec3 blue = vec3(30.0 / 255.0, 70.0 / 255.0, 235.0 / 255.0);
      vec3 col = mix(blue, red, band);

      // Glossy sheen along each colour boundary for a lively, glassy feel.
      float edge = 1.0 - abs(band - 0.5) * 2.0;
      col += pow(edge, 6.0) * 0.45;

      gl_FragColor = vec4(col, 1.0);
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
