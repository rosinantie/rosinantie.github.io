(function () {
  // Clicking a post image opens it full-screen with zoom + pan.
  // Desktop: wheel to zoom, drag to pan, double-click to toggle zoom.
  // Touch: pinch to zoom, drag to pan, double-tap to toggle. Esc / × / tap-backdrop closes.
  var images = document.querySelectorAll('article img');
  if (!images.length) return;

  var MIN = 1, MAX = 6;

  var style = document.createElement('style');
  style.textContent = [
    '.lightbox-overlay{position:fixed;inset:0;z-index:9999;display:none;',
    'align-items:center;justify-content:center;overflow:hidden;',
    'background:rgba(0,0,0,.92);opacity:0;transition:opacity .2s ease;touch-action:none;}',
    '.lightbox-overlay.is-open{opacity:1;}',
    '.lightbox-overlay img{max-width:100vw;max-height:100vh;width:auto;height:auto;',
    'object-fit:contain;user-select:none;-webkit-user-drag:none;',
    'transform-origin:center center;will-change:transform;',
    'transition:transform .15s ease;touch-action:none;cursor:zoom-in;}',
    '.lightbox-overlay img.zoomed{cursor:grab;}',
    '.lightbox-overlay img.grabbing{cursor:grabbing;transition:none;}',
    '.lightbox-close{position:fixed;top:14px;right:18px;z-index:10000;',
    'width:44px;height:44px;border:0;border-radius:50%;cursor:pointer;',
    'background:rgba(0,0,0,.5);color:#fff;font-size:26px;line-height:44px;',
    'text-align:center;padding:0;}',
    '.lightbox-close:hover{background:rgba(0,0,0,.8);}',
    '.lightbox-hint{position:fixed;bottom:14px;left:50%;transform:translateX(-50%);',
    'z-index:10000;color:rgba(255,255,255,.65);font-size:13px;pointer-events:none;',
    'background:rgba(0,0,0,.4);padding:5px 12px;border-radius:20px;}',
    'article img{cursor:zoom-in;}'
  ].join('');
  document.head.appendChild(style);

  var overlay = document.createElement('div');
  overlay.className = 'lightbox-overlay';
  overlay.setAttribute('role', 'dialog');
  overlay.setAttribute('aria-modal', 'true');
  overlay.setAttribute('aria-label', 'Image preview');

  var big = document.createElement('img');
  var closeBtn = document.createElement('button');
  closeBtn.className = 'lightbox-close';
  closeBtn.type = 'button';
  closeBtn.setAttribute('aria-label', 'Close');
  closeBtn.innerHTML = '&times;';
  var hint = document.createElement('div');
  hint.className = 'lightbox-hint';

  overlay.appendChild(big);
  overlay.appendChild(closeBtn);
  overlay.appendChild(hint);
  document.body.appendChild(overlay);

  var scale = 1, tx = 0, ty = 0;
  var pointers = {};       // active pointers: id -> {x, y}
  var startDist = 0, startScale = 1;
  var lastMid = null;
  var moved = false, lastTap = 0;

  function apply() {
    big.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + scale + ')';
    big.classList.toggle('zoomed', scale > 1);
  }

  function reset() {
    scale = 1; tx = 0; ty = 0;
    apply();
  }

  function center() {
    var r = overlay.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  // Zoom by factor f around a screen point (px, py), keeping that point fixed.
  function zoomAt(f, px, py) {
    var next = Math.min(MAX, Math.max(MIN, scale * f));
    f = next / scale;
    var c = center();
    var fx = px - c.x, fy = py - c.y;
    tx = tx * f + fx * (1 - f);
    ty = ty * f + fy * (1 - f);
    scale = next;
    if (scale <= MIN + 0.001) { tx = 0; ty = 0; scale = MIN; }
    apply();
  }

  function open(src, alt) {
    reset();
    big.src = src;
    big.alt = alt || '';
    hint.textContent = ('ontouchstart' in window)
      ? 'Pinch to zoom · drag to pan · tap ✕ to close'
      : 'Scroll to zoom · drag to pan · double-click to reset';
    overlay.style.display = 'flex';
    void overlay.offsetWidth;
    overlay.classList.add('is-open');
    document.body.style.overflow = 'hidden';
  }

  function close() {
    overlay.classList.remove('is-open');
    document.body.style.overflow = '';
    setTimeout(function () { overlay.style.display = 'none'; big.src = ''; reset(); }, 200);
  }

  images.forEach(function (img) {
    img.addEventListener('click', function () { open(img.currentSrc || img.src, img.alt); });
  });

  closeBtn.addEventListener('click', function (e) { e.stopPropagation(); close(); });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && overlay.classList.contains('is-open')) close();
  });

  // Desktop wheel zoom, centered on the cursor.
  overlay.addEventListener('wheel', function (e) {
    e.preventDefault();
    zoomAt(e.deltaY < 0 ? 1.15 : 1 / 1.15, e.clientX, e.clientY);
  }, { passive: false });

  // Pointer-based pinch + pan (works for mouse and touch).
  big.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    big.setPointerCapture(e.pointerId);
    pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
    moved = false;
    var ids = Object.keys(pointers);
    if (ids.length === 2) {
      var a = pointers[ids[0]], b = pointers[ids[1]];
      startDist = Math.hypot(a.x - b.x, a.y - b.y);
      startScale = scale;
      lastMid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
    } else {
      big.classList.add('grabbing');
    }
  });

  big.addEventListener('pointermove', function (e) {
    if (!pointers[e.pointerId]) return;
    var prev = pointers[e.pointerId];
    var dx = e.clientX - prev.x, dy = e.clientY - prev.y;
    pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
    var ids = Object.keys(pointers);

    if (ids.length === 2) {
      var a = pointers[ids[0]], b = pointers[ids[1]];
      var dist = Math.hypot(a.x - b.x, a.y - b.y);
      var mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      if (startDist > 0) {
        var target = Math.min(MAX, Math.max(MIN, startScale * (dist / startDist)));
        zoomAt(target / scale, mid.x, mid.y);
      }
      if (lastMid) { tx += mid.x - lastMid.x; ty += mid.y - lastMid.y; apply(); }
      lastMid = mid;
      moved = true;
    } else if (ids.length === 1 && scale > 1) {
      tx += dx; ty += dy; moved = true; apply();
    } else if (Math.abs(dx) + Math.abs(dy) > 3) {
      moved = true;
    }
  });

  function endPointer(e) {
    if (pointers[e.pointerId]) delete pointers[e.pointerId];
    if (Object.keys(pointers).length < 2) { startDist = 0; lastMid = null; }
    big.classList.remove('grabbing');

    // Tap / click with no drag: double-tap toggles zoom, single tap on 1x closes.
    if (!moved) {
      var now = Date.now();
      if (now - lastTap < 300) {
        if (scale > 1) reset();
        else zoomAt(2.5, e.clientX, e.clientY);
        lastTap = 0;
      } else {
        lastTap = now;
        if (scale <= 1) { setTimeout(function () { if (Date.now() - lastTap >= 290) close(); }, 300); }
      }
    }
  }
  big.addEventListener('pointerup', endPointer);
  big.addEventListener('pointercancel', endPointer);

  // Tapping the dark area (not the image) closes.
  overlay.addEventListener('pointerdown', function (e) {
    if (e.target === overlay) close();
  });
})();
