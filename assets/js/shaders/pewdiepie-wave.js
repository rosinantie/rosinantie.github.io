(function () {
  const canvas = document.getElementById('shader-canvas');
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
    void main() { gl_Position = vec4(a_position, 0.0, 1.0); }
  `;

  // PewDiePie brand bars: warped red/blue diagonal stripes drifting upward.
  const fsSource = `
    precision mediump float;
    uniform float iTime;
    uniform vec2 iResolution;

    const float tiling = 12.0;
    const float PI = 3.14159265359;
    const float warpScale = 0.03;

    void main() {
      vec2 p = gl_FragCoord.xy / iResolution.xy;
      p.y *= iResolution.y / iResolution.x;

      p.y += sin(p.y * PI * 2.0) * (warpScale - 0.02);
      p.y -= cos(p.y * PI * 5.0) * warpScale;
      p.y -= sin(p.x * PI * 10.0) * (warpScale + 0.03);

      p.y += fract(p.x + p.y) * 0.85;
      p.y -= iTime * 0.02;
      p.y *= tiling;

      float fractVal = floor(fract(p.y) + 0.5);

      vec3 col1 = vec3(228.0 / 255.0, 5.0 / 255.0, 50.0 / 255.0);
      vec3 col2 = vec3(10.0 / 255.0, 0.0 / 255.0, 1.0 / 255.0);
      vec3 finalCol = mix(col1, col2, fractVal);

      gl_FragColor = vec4(finalCol, 1.0);
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

  const prog = gl.createProgram();
  gl.attachShader(prog, compile(gl.VERTEX_SHADER, vsSource));
  gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, fsSource));
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
    console.error(gl.getProgramInfoLog(prog));
    return;
  }
  gl.useProgram(prog);

  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
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
