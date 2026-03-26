// Matrix Rain WebGL Shader
(function () {
  const canvas = document.getElementById('matrix-canvas');
  if (!canvas) return;

  const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
  if (!gl) {
    console.warn('WebGL not supported');
    return;
  }

  function resize() {
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
    gl.viewport(0, 0, canvas.width, canvas.height);
  }
  resize();
  window.addEventListener('resize', resize);

  // Vertex shader
  const vsSource = `
    attribute vec2 a_position;
    void main() {
      gl_Position = vec4(a_position, 0.0, 1.0);
    }
  `;

  // Fragment shader - Matrix digital rain
  const fsSource = `
    precision mediump float;
    uniform float u_time;
    uniform vec2 u_resolution;

    float random(vec2 st) {
      return fract(sin(dot(st, vec2(12.9898, 78.233))) * 43758.5453);
    }

    float char_glow(vec2 uv, float col_id, float row_id, float speed) {
      float t = u_time * speed;
      float rand_offset = random(vec2(col_id, 0.0));
      float drop = fract(t * (0.3 + rand_offset * 0.5) + rand_offset * 100.0);
      float y_pos = 1.0 - drop;
      float dist = row_id - y_pos;
      // trail effect
      float trail = smoothstep(0.0, 0.6, dist) * step(0.0, dist);
      trail *= (1.0 - dist * 1.5);
      trail = max(trail, 0.0);
      // bright head
      float head = smoothstep(0.02, 0.0, abs(row_id - y_pos));
      return trail * 0.6 + head * 1.0;
    }

    void main() {
      vec2 uv = gl_FragCoord.xy / u_resolution;
      float columns = 40.0;
      float col_width = 1.0 / columns;

      float col_id = floor(uv.x / col_width);
      float row_id = uv.y;

      float brightness = 0.0;

      // Multiple layers of rain
      brightness += char_glow(uv, col_id, row_id, 1.0);
      brightness += char_glow(uv, col_id + 100.0, row_id, 0.7) * 0.5;
      brightness += char_glow(uv, col_id + 200.0, row_id, 1.3) * 0.3;

      // Flicker
      float flicker = 0.9 + 0.1 * sin(u_time * 10.0 + col_id * 3.0);
      brightness *= flicker;

      // Green matrix color
      vec3 color = vec3(0.0, brightness, brightness * 0.3);

      // Subtle scanline
      float scanline = 0.95 + 0.05 * sin(gl_FragCoord.y * 2.0);
      color *= scanline;

      gl_FragColor = vec4(color, brightness * 0.85);
    }
  `;

  function createShader(type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      console.error('Shader error:', gl.getShaderInfoLog(shader));
      gl.deleteShader(shader);
      return null;
    }
    return shader;
  }

  const vs = createShader(gl.VERTEX_SHADER, vsSource);
  const fs = createShader(gl.FRAGMENT_SHADER, fsSource);

  const program = gl.createProgram();
  gl.attachShader(program, vs);
  gl.attachShader(program, fs);
  gl.linkProgram(program);

  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    console.error('Program link error:', gl.getProgramInfoLog(program));
    return;
  }

  gl.useProgram(program);

  // Fullscreen quad
  const vertices = new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]);
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, vertices, gl.STATIC_DRAW);

  const a_position = gl.getAttribLocation(program, 'a_position');
  gl.enableVertexAttribArray(a_position);
  gl.vertexAttribPointer(a_position, 2, gl.FLOAT, false, 0, 0);

  const u_time = gl.getUniformLocation(program, 'u_time');
  const u_resolution = gl.getUniformLocation(program, 'u_resolution');

  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

  function render(time) {
    time *= 0.001; // ms to seconds
    gl.uniform1f(u_time, time);
    gl.uniform2f(u_resolution, canvas.width, canvas.height);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    requestAnimationFrame(render);
  }
  requestAnimationFrame(render);
})();
