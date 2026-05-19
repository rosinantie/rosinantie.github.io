(function () {
  const style = document.createElement('style');
  style.textContent = `
    #wave-canvas {
      position: fixed;
      top: 0;
      left: 0;
      width: 100vw;
      height: 100vh;
      pointer-events: none;
      z-index: 9999;
      -webkit-mask:
        linear-gradient(#000, #000) center / calc(100% - 28px) calc(100% - 28px) no-repeat,
        linear-gradient(#000, #000);
      -webkit-mask-composite: xor;
              mask-composite: exclude;
    }
  `;
  document.head.appendChild(style);

  const canvas = document.createElement('canvas');
  canvas.id = 'wave-canvas';
  canvas.setAttribute('aria-hidden', 'true');
  document.body.appendChild(canvas);

  const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
  if (!gl) return;

  const dpr = Math.min(window.devicePixelRatio || 1, 2);

  function resize() {
    const w = window.innerWidth;
    const h = window.innerHeight;
    canvas.width = Math.floor(w * dpr);
    canvas.height = Math.floor(h * dpr);
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';
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

      vec3 col1 = vec3(228.0/255.0, 5.0/255.0, 50.0/255.0);
      vec3 col2 = vec3(10.0/255.0, 0.0/255.0, 1.0/255.0);
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
