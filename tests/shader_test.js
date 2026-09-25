// Compiles Master.glsl with every SameBoy filter as GLSL ES 3.00 (WebGL2 uses the same shading
// language as OpenGL ES 3.0) and renders a test pattern with each, with and without frame blending.
// Usage: node tests/shader_test.js app/src/main/assets/shaders [output dir for PNGs]
// Needs Playwright with Chromium: npm install playwright && npx playwright install chromium
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const dir = process.argv[2];
const out = process.argv[3];
if (out) fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  await page.setContent('<canvas id=c width=640 height=576></canvas>');
  const master = fs.readFileSync(path.join(dir, 'Master.glsl'), 'utf8');
  const filters = fs.readdirSync(dir).filter(f => f.endsWith('.fsh')).sort();
  let failures = 0;
  for (const f of filters) {
    const src = master.replace('{filter}', fs.readFileSync(path.join(dir, f), 'utf8'));
    for (const mode of [0, 3]) {
      const res = await page.evaluate(({ src, mode }) => {
        const gl = document.getElementById('c').getContext('webgl2', { preserveDrawingBuffer: true });
        const vs = gl.createShader(gl.VERTEX_SHADER);
        gl.shaderSource(vs, '#version 300 es\nin vec2 p;void main(){gl_Position=vec4(p,0.0,1.0);}');
        gl.compileShader(vs);
        const fs = gl.createShader(gl.FRAGMENT_SHADER);
        gl.shaderSource(fs, src);
        gl.compileShader(fs);
        if (!gl.getShaderParameter(fs, gl.COMPILE_STATUS)) return { error: gl.getShaderInfoLog(fs) };
        const prog = gl.createProgram();
        gl.attachShader(prog, vs); gl.attachShader(prog, fs);
        gl.bindAttribLocation(prog, 0, 'p');
        gl.linkProgram(prog);
        if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return { error: gl.getProgramInfoLog(prog) };
        // 160x144 test pattern: diagonal stripes, a gradient and a checkerboard.
        const w = 160, h = 144, px = new Uint8Array(w * h * 4);
        for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
          const i = (y * w + x) * 4;
          let c;
          if (y < 48) c = ((x + y) >> 3) & 1 ? [224, 248, 208] : [8, 24, 32];
          else if (y < 96) c = [x * 255 / w, 128, 255 - x * 255 / w];
          else c = ((x >> 2) + (y >> 2)) & 1 ? [255, 255, 255] : [52, 104, 86];
          px.set([...c, 255], i);
        }
        const tex = [0, 1].map(unit => {
          const t = gl.createTexture();
          gl.activeTexture(gl.TEXTURE0 + unit);
          gl.bindTexture(gl.TEXTURE_2D, t);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
          gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, px);
          return t;
        });
        gl.useProgram(prog);
        gl.uniform1i(gl.getUniformLocation(prog, 'image'), 0);
        gl.uniform1i(gl.getUniformLocation(prog, 'previous_image'), 1);
        gl.uniform1i(gl.getUniformLocation(prog, 'frame_blending_mode'), mode);
        gl.uniform2f(gl.getUniformLocation(prog, 'output_resolution'), 640, 576);
        gl.uniform2f(gl.getUniformLocation(prog, 'origin'), 0, 0);
        const buf = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, buf);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
        gl.viewport(0, 0, 640, 576);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        const err = gl.getError();
        if (err) return { error: 'GL error ' + err };
        const out = new Uint8Array(4);
        gl.readPixels(8, 560, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, out); // top-left stripe area
        return { ok: true, pixel: Array.from(out), png: document.getElementById('c').toDataURL() };
      }, { src, mode });
      if (res.error) { failures++; console.log(`FAIL ${f} (blend ${mode}): ${res.error.trim()}`); break; }
      console.log(`ok   ${f} (blend ${mode}) top-left pixel ${res.pixel}`);
      if (out && mode === 0) fs.writeFileSync(path.join(out, f.replace('.fsh', '.png')), Buffer.from(res.png.split(',')[1], 'base64'));
    }
  }
  await browser.close();
  console.log(failures ? `${failures} filter(s) failed` : 'All filters compiled and rendered');
  process.exit(failures ? 1 : 0);
})();
