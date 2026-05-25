(function () {
  function detectLang(block, codeEl) {
    if (codeEl && codeEl.dataset && codeEl.dataset.lang) return codeEl.dataset.lang;
    var m = block.className.match(/language-([\w+-]+)/);
    if (m) return m[1];
    if (codeEl) {
      var cm = codeEl.className.match(/language-([\w+-]+)/);
      if (cm) return cm[1];
    }
    return 'code';
  }

  function wireBlock(block) {
    if (block.dataset.codeReady === '1') return;
    if (block.classList.contains('language-plaintext')) return;

    var codeEl = block.querySelector('pre > code') || block.querySelector('code');
    var lang = detectLang(block, codeEl);

    var header = document.createElement('div');
    header.className = 'code-header';

    var langLabel = document.createElement('span');
    langLabel.className = 'code-lang';
    langLabel.textContent = lang;

    var copyBtn = document.createElement('button');
    copyBtn.className = 'copy-btn';
    copyBtn.type = 'button';
    copyBtn.textContent = 'Copy';

    copyBtn.addEventListener('click', function () {
      var source = codeEl || block.querySelector('pre') || block;
      var text = source.innerText;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () {
          copyBtn.textContent = 'Copied';
          setTimeout(function () { copyBtn.textContent = 'Copy'; }, 1500);
        }).catch(function () {
          copyBtn.textContent = 'Failed';
          setTimeout(function () { copyBtn.textContent = 'Copy'; }, 1500);
        });
      } else {
        var ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); copyBtn.textContent = 'Copied'; }
        catch (e) { copyBtn.textContent = 'Failed'; }
        document.body.removeChild(ta);
        setTimeout(function () { copyBtn.textContent = 'Copy'; }, 1500);
      }
    });

    header.appendChild(langLabel);
    header.appendChild(copyBtn);
    block.insertBefore(header, block.firstChild);
    block.dataset.codeReady = '1';
  }

  function init() {
    var blocks = document.querySelectorAll('div.highlighter-rouge, figure.highlight');
    blocks.forEach(wireBlock);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
