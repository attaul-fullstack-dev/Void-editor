// ── ELEMENTS ──
const textarea    = document.getElementById('editor-textarea');
const hlLayer     = document.getElementById('highlight-layer');
const syntaxLayer = document.getElementById('syntax-layer');
const activeLineHL = document.getElementById('active-line-highlight');
const lineNums    = document.getElementById('line-numbers');
const scrollCont  = document.getElementById('scroll-container');
const scrollInner = document.getElementById('scroll-inner');
const sbLn        = document.getElementById('sb-ln');
const sbCol       = document.getElementById('sb-col');
const sbChar      = document.getElementById('sb-char');
const sbMatch     = document.getElementById('sb-match');
const sbNav       = document.getElementById('sb-nav');
const popupDialog = document.getElementById('popup-dialog');
const searchInput = document.getElementById('search-input');
const replaceInp  = document.getElementById('replace-input');
const searchHint  = document.getElementById('search-hint');
const navCounter  = document.getElementById('nav-counter');

const fileNameInp = document.getElementById('file-name-input');
const btnUndo     = document.getElementById('btn-undo');
const btnRedo     = document.getElementById('btn-redo');
const btnWordWrap = document.getElementById('btn-wordwrap');
const fontSizeSelect = document.getElementById('font-size-select');
const wrapLabel   = document.getElementById('wrap-label');

// ── STATE ──
let matches = [];
let currentMatchIdx = -1;
let replacedRanges = []; // { pos, len } — highlight merah setelah replace
let activeQuery = ''; // query aktif untuk highlight, terpisah dari popup state
let wordWrapOn = false;
let isDirty = false;
let lastSavedContent = '';
// Cermin read-only dari activeRemotePath milik native. HANYA untuk keperluan tampilan
// (judul file + pesan toast setelah simpan). Routing Save tetap sepenuhnya diputuskan
// oleh MainActivity, jadi nilai ini tidak boleh dijadikan sumber kebenaran.
let remoteFileHint = null;

// ── SYNTAX HIGHLIGHTING ──
function renderSyntaxHighlight(code) {
  // Tokenize with regex-based approach for JS syntax
  const tokens = [];
  const re = /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|(`(?:[^`\\]|\\.)*`|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(\b(?:function|const|let|var|return|if|else|for|while|class|import|export|default|new|this|typeof|instanceof|switch|case|break|continue|throw|try|catch|finally|async|await|yield|do|in|of|void|delete|debugger|extends|super|static|get|set|from|as|with|enum|implements|interface|package|private|protected|public)\b)|(\b(?:console|Math|JSON|Object|Array|String|Number|Boolean|Date|RegExp|Map|Set|WeakMap|WeakSet|Promise|Symbol|Error|TypeError|RangeError|SyntaxError|parseInt|parseFloat|isNaN|isFinite|undefined|null|true|false|NaN|Infinity|globalThis|window|document|navigator|fetch|setTimeout|setInterval|clearTimeout|clearInterval|requestAnimationFrame|cancelAnimationFrame|alert|confirm|prompt)\b)|(\b(?:0[xX][0-9a-fA-F]+|0[oO][0-7]+|0[bB][01]+|\d+\.?\d*(?:[eE][+-]?\d+)?|\.\d+(?:[eE][+-]?\d+)?)\b)/g;
  
  let match;
  while ((match = re.exec(code)) !== null) {
    let cls = '';
    if (match[1]) cls = 'syn-comment';
    else if (match[2]) cls = 'syn-string';
    else if (match[3]) cls = 'syn-keyword';
    else if (match[4]) cls = 'syn-builtin';
    else if (match[5]) cls = 'syn-number';
    if (cls) {
      tokens.push({ pos: match.index, end: match.index + match[0].length, cls });
    }
  }

  // Build highlighted HTML
  let result = '';
  let cursor = 0;
  for (const tk of tokens) {
    if (tk.pos < cursor) continue;
    result += escHtml(code.slice(cursor, tk.pos));
    result += `<span class="${tk.cls}">${escHtml(code.slice(tk.pos, tk.end))}</span>`;
    cursor = tk.end;
  }
  result += escHtml(code.slice(cursor));
  syntaxLayer.innerHTML = result;
}

// ── ACTIVE LINE HIGHLIGHT ──
function updateActiveLine(lineNum) {
  const lineH = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--editor-line-height'));
  const padTop = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--editor-padding-top'));
  activeLineHL.style.top = (padTop + (lineNum - 1) * lineH) + 'px';
  activeLineHL.style.height = lineH + 'px';
}

// ── WORD WRAP TOGGLE ──
function toggleWordWrap() {
  wordWrapOn = !wordWrapOn;
  textarea.classList.toggle('word-wrap', wordWrapOn);
  syntaxLayer.classList.toggle('word-wrap', wordWrapOn);
  hlLayer.classList.toggle('word-wrap', wordWrapOn);
  btnWordWrap.classList.toggle('active', wordWrapOn);
  btnWordWrap.setAttribute('aria-pressed', String(wordWrapOn));
  wrapLabel.textContent = 'Wrap';
  localStorage.setItem('voidedit-wordwrap', wordWrapOn ? '1' : '0');
  
  // Sembunyikan gutter (nomor baris) saat word wrap aktif
  // karena text yang melipat (wrap) akan merusak sinkronisasi tinggi nomor baris.
  const gutter = document.getElementById('gutter');
  if (wordWrapOn) {
    gutter.style.display = 'none';
  } else {
    gutter.style.display = 'block';
  }

  remeasureEditor();
  fullUpdate();
}

// ── FONT SIZE CONTROL ──
function changeFontSize(size) {
  const px = parseInt(size, 10);
  // Rentang sengaja dibuka sampai 4px (opsi terkecil di dropdown). WebView juga harus
  // menurunkan minimumFontSize-nya, kalau tidak nilai di bawah 8px akan dinaikkan paksa.
  if (isNaN(px) || px < 4 || px > 96) return;
  const lineH = Math.max(px + 2, Math.round(px * 1.6));
  document.documentElement.style.setProperty('--editor-font-size', px + 'px');
  document.documentElement.style.setProperty('--editor-line-height', lineH + 'px');
  fontSizeSelect.value = px;
  localStorage.setItem('voidedit-fontsize', px);
  remeasureEditor();
  fullUpdate();
}

// ── INIT WORD WRAP & FONT SIZE FROM LOCALSTORAGE ──
function initSettings() {
  // Font size
  const savedSize = localStorage.getItem('voidedit-fontsize');
  if (savedSize) {
    changeFontSize(savedSize);
  }
  // Word wrap
  const savedWrap = localStorage.getItem('voidedit-wordwrap');
  if (savedWrap === '1') {
    wordWrapOn = false; // toggleWordWrap will flip it
    toggleWordWrap();
  }
}

// ── UNDO/REDO ──
const MAX_HISTORY = 200;
let undoStack = [];
let redoStack = [];
let isUndoRedo = false;

function pushHistory(val) {
  if (isUndoRedo) return;
  const pos = textarea.selectionStart;
  if (undoStack.length && undoStack[undoStack.length - 1].val === val) return;
  undoStack.push({ val, pos });
  if (undoStack.length > MAX_HISTORY) undoStack.shift();
  redoStack = [];
  updateUndoRedoBtns();
}

function restoreSnapshot(snap) {
  isUndoRedo = true;
  textarea.value = snap.val;
  textarea.selectionStart = textarea.selectionEnd = snap.pos;
  isUndoRedo = false;
  fullUpdate();
  updateUndoRedoBtns();
}

function doUndo() {
  if (undoStack.length <= 1) return;
  redoStack.push(undoStack.pop());
  restoreSnapshot(undoStack[undoStack.length - 1]);
}

function doRedo() {
  if (!redoStack.length) return;
  const snap = redoStack.pop();
  if (undoStack.length >= MAX_HISTORY) undoStack.shift();
  undoStack.push(snap);
  restoreSnapshot(snap);
}

function updateUndoRedoBtns() {
  btnUndo.disabled = undoStack.length <= 1;
  btnRedo.disabled = redoStack.length === 0;
}

// ── INITIAL CONTENT ──
const INITIAL = `// VoidEdit — Mobile Code Editor
// Ketik atau buka file untuk mulai.

function greet(name) {
  const msg = \`Halo, \${name}!\`;
  console.log(msg);
  return msg;
}

const result = greet("Dani");
console.log(result);

// Fitur:
// ✓ Ghost Textarea
// ✓ Nomor baris sinkron pixel-perfect
// ✓ Search & Replace
// ✓ Open / Save file
`;

textarea.value = INITIAL;
undoStack.push({ val: INITIAL, pos: 0 });
updateUndoRedoBtns();

// ── SYNC SCROLL & SIZE ──

function syncGutterScroll() {
  lineNums.style.transform = `translateY(-${scrollCont.scrollTop}px)`;
}

scrollCont.addEventListener('scroll', () => {
  syncGutterScroll();
}, { passive: true });

// ── TEXTAREA SIZING ──
// Textarea harus mengisi scroll-inner. Semua layer di-reset sebelum pengukuran agar
// ukuran font/wrap lama tidak meninggalkan kanvas tinggi atau lebar di belakang teks.
function resizeTextarea() {
  const containerW = scrollCont.clientWidth;
  const containerH = scrollCont.clientHeight;
  const layers = [textarea, syntaxLayer, hlLayer];

  scrollInner.style.width = containerW + 'px';
  scrollInner.style.height = containerH + 'px';
  layers.forEach((layer) => {
    layer.style.width = wordWrapOn ? containerW + 'px' : '100%';
    layer.style.height = '0px';
  });

  const contentH = textarea.scrollHeight;
  const contentW = wordWrapOn ? containerW : textarea.scrollWidth;
  const finalH = Math.max(contentH, containerH);
  const finalW = Math.max(contentW, containerW);

  scrollInner.style.width = finalW + 'px';
  scrollInner.style.height = finalH + 'px';
  layers.forEach((layer) => {
    layer.style.width = finalW + 'px';
    layer.style.height = finalH + 'px';
  });
}

function remeasureEditor() {
  resizeTextarea();
  requestAnimationFrame(() => {
    resizeTextarea();
    syncGutterScroll();
  });
}

// ── LINE NUMBERS ──
function renderLineNumbers(count, activeLine) {
  let html = '';
  for (let i = 1; i <= count; i++) {
    html += `<span class="line-num${i === activeLine ? ' active' : ''}">${i}</span>`;
  }
  lineNums.innerHTML = html;
}

// ── HIGHLIGHT LAYER ──
function escHtml(s) {
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function renderHighlight(query = '', curIdx = -1) {
  const code = textarea.value;
  const events = [];

  // Search matches
  if (query) {
    const lq = query.toLowerCase();
    const lc = code.toLowerCase();
    let i = 0, mIdx = 0;
    while (true) {
      const pos = lc.indexOf(lq, i);
      if (pos === -1) break;
      events.push({ pos, end: pos + query.length, cls: 'hl-match' + (mIdx === curIdx ? ' current' : '') });
      i = pos + query.length; mIdx++;
    }
  }

  // Replaced ranges
  for (const r of replacedRanges) {
    if (r.len > 0) events.push({ pos: r.pos, end: r.pos + r.len, cls: 'hl-replaced' });
  }

  // Sort by position, remove overlaps (keep first event per range)
  events.sort((a, b) => a.pos - b.pos);
  const deduped = [];
  let lastEnd = 0;
  for (const ev of events) {
    if (ev.pos < lastEnd) continue; // skip overlap
    deduped.push(ev);
    lastEnd = ev.end;
  }

  let result = '';
  let cursor = 0;
  for (const ev of deduped) {
    if (ev.pos < cursor) continue;
    result += escHtml(code.slice(cursor, ev.pos));
    result += `<mark class="${ev.cls}">${escHtml(code.slice(ev.pos, ev.end))}</mark>`;
    cursor = ev.end;
  }
  result += escHtml(code.slice(cursor));
  hlLayer.innerHTML = result;
}

// ── FIND MATCHES ──
function findMatches(query) {
  matches = [];
  if (!query) return;
  const lq = query.toLowerCase();
  const lc = textarea.value.toLowerCase();
  let i = 0;
  while (true) {
    const pos = lc.indexOf(lq, i);
    if (pos === -1) break;
    matches.push(pos);
    i = pos + lq.length;
  }
}


// ── NAVIGATE TO MATCH ──
function scrollToMatch(idx) {
  if (!matches.length) return;
  const pos = matches[idx];
  const before = textarea.value.slice(0, pos);
  const lineIdx = (before.match(/\n/g) || []).length;
  const lineH = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--editor-line-height'));
  const padTop = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--editor-padding-top'));
  const targetY = padTop + lineIdx * lineH - scrollCont.clientHeight / 2 + lineH;
  scrollCont.scrollTop = Math.max(0, targetY);
}

// ── DEBOUNCE UTIL ──
function debounce(fn, ms) {
  let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

// ── FULL UPDATE ──
let rafId = null;
function fullUpdate() {
  // Cancel rAF sebelumnya agar selalu pakai state terbaru
  if (rafId !== null) cancelAnimationFrame(rafId);
  rafId = requestAnimationFrame(() => {
    rafId = null;
    const code = textarea.value;
    const lines = code.split('\n');
    const numLines = lines.length;

    const selStart = textarea.selectionStart;
    const before = code.slice(0, selStart);
    const ln = (before.match(/\n/g) || []).length + 1;
    const col = selStart - before.lastIndexOf('\n');

    sbLn.textContent   = ln;
    sbCol.textContent  = col;
    sbChar.textContent = code.length;

    renderLineNumbers(numLines, ln);

    // Syntax highlighting
    renderSyntaxHighlight(code);

    // Active line highlight
    updateActiveLine(ln);

    // Highlight pakai activeQuery + currentMatchIdx terbaru
    renderHighlight(activeQuery, currentMatchIdx);

    resizeTextarea();
    syncGutterScroll();
  });
}

// ── TEXTAREA EVENTS ──
let isProgrammaticChange = false;
textarea.addEventListener('input', () => {
  if (isProgrammaticChange) return;
  replacedRanges = [];
  isDirty = true;
  pushHistory(textarea.value);
  fullUpdate();
});
textarea.addEventListener('keyup', fullUpdate);
textarea.addEventListener('click', fullUpdate);
textarea.addEventListener('keydown', (e) => {
  // Undo: Ctrl+Z
  if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
    e.preventDefault(); doUndo(); return;
  }
  // Redo: Ctrl+Y atau Ctrl+Shift+Z
  if ((e.ctrlKey || e.metaKey) && (e.key === 'y' || (e.key === 'z' && e.shiftKey))) {
    e.preventDefault(); doRedo(); return;
  }
  // Tab support
  if (e.key === 'Tab') {
    e.preventDefault();
    const s = textarea.selectionStart;
    const v = textarea.value;
    textarea.value = v.slice(0, s) + '  ' + v.slice(textarea.selectionEnd);
    textarea.selectionStart = textarea.selectionEnd = s + 2;
    pushHistory(textarea.value);
    fullUpdate();
  }
});

// ── POPUP ──
function openPopup() {
  popupDialog.showModal();
  searchInput.focus();
}

function closePopup() {
  popupDialog.close();
  // highlight & nav di statusbar tetap aktif jika ada matches
}

function clearSearch() {
  matches = [];
  currentMatchIdx = -1;
  replacedRanges = [];
  activeQuery = '';
  renderHighlight('');
  sbMatch.textContent = '';
  sbNav.classList.remove('visible');
  navCounter.textContent = '—';
}

// Tutup hanya jika klik backdrop
// Fallback for browsers without closedby support
if (!('closedBy' in HTMLDialogElement.prototype)) {
  popupDialog.addEventListener('click', (event) => {
    // 1. When clicking the backdrop, the event target is the dialog element itself.
    // Ignore clicks where the target is a child element inside the dialog.
    if (event.target !== popupDialog) return;

    // 2. Check if the click coordinates fall within the dialog's content box.
    const rect = popupDialog.getBoundingClientRect();
    const isDialogContent = (
      rect.top <= event.clientY &&
      event.clientY <= rect.top + rect.height &&
      rect.left <= event.clientX &&
      event.clientX <= rect.left + rect.width
    );

    if (isDialogContent) return;

    // 3. Since the click was outside the content area (on the backdrop), manually close the dialog.
    popupDialog.close();
  });
}

// ── SEARCH LOGIC ──
// Saat mengetik → hanya reset jika query benar-benar berubah
searchInput.addEventListener('input', () => {
  const newQ = searchInput.value.trim();
  // Tidak reset jika value sama dengan activeQuery (cegah false reset dari focus/composing)
  if (newQ === activeQuery) return;
  // Reset state pencarian
  matches = [];
  currentMatchIdx = -1;
  activeQuery = '';
  replacedRanges = [];
  renderHighlight('');
  sbMatch.textContent = '';
  navCounter.textContent = '—';
  searchHint.textContent = newQ.length > 0 ? 'Tekan Enter untuk mencari' : '';
  searchHint.className = newQ.length > 0 ? 'warn' : '';
});

// Enter → eksekusi pencarian (Shift+Enter = newline biasa)
searchInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    doSearch();
  }
});

function doSearch() {
  const q = searchInput.value.trim().split('\r\n').join('\n').split('\r').join('\n');
  if (!q) {
    searchHint.textContent = 'Masukkan kata yang ingin dicari.';
    searchHint.className = 'warn';
    return;
  }
  findMatches(q);
  if (matches.length === 0) {
    searchHint.textContent = 'Tidak ditemukan.';
    searchHint.className = 'warn';
    navCounter.textContent = '—';
    sbMatch.textContent = '0';
    renderHighlight(q, -1);
    return;
  }
  currentMatchIdx = 0;
  activeQuery = q;
  searchHint.textContent = `${matches.length} match ditemukan.`;
  searchHint.className = 'ok';
  navCounter.textContent = `${currentMatchIdx + 1}/${matches.length}`;
  // Tampilkan nav di statusbar
  sbMatch.textContent = `${currentMatchIdx + 1}/${matches.length}`;
  sbNav.classList.add('visible');
  renderHighlight(q, currentMatchIdx);
  scrollToMatch(currentMatchIdx);
}

function navMatch(dir) {
  if (!matches.length) return;
  currentMatchIdx = (currentMatchIdx + dir + matches.length) % matches.length;
  navCounter.textContent = `${currentMatchIdx + 1}/${matches.length}`;
  sbMatch.textContent = `${currentMatchIdx + 1}/${matches.length}`;
  renderHighlight(activeQuery, currentMatchIdx);
  scrollToMatch(currentMatchIdx);
}

function replaceCurrent() {
  const q = searchInput.value.trim().split('\r\n').join('\n').split('\r').join('\n');
  if (!q) return;

  // Re-scan dulu dari teks terkini — hindari posisi stale
  findMatches(q);
  if (!matches.length) {
    // Teks sudah tidak ada match — update UI supaya user tahu
    activeQuery = '';
    currentMatchIdx = -1;
    replacedRanges = [];
    searchHint.textContent = 'Tidak ditemukan. Ketik ulang atau ubah kata cari.';
    searchHint.className = 'warn';
    navCounter.textContent = '—';
    sbMatch.textContent = '0';
    renderHighlight('');
    return;
  }
  if (currentMatchIdx === -1 || currentMatchIdx >= matches.length) currentMatchIdx = 0;

  const r = replaceInp.value;
  const pos = matches[currentMatchIdx];
  const v = textarea.value;

  // Lakukan replace (flag agar textarea input event tidak clear replacedRanges)
  isProgrammaticChange = true;
  textarea.value = v.slice(0, pos) + r + v.slice(pos + q.length);
  textarea.selectionStart = textarea.selectionEnd = pos + r.length;
  isProgrammaticChange = false;
  pushHistory(textarea.value);

  // Simpan replaced range
  const pendingRange = r.length > 0 ? { pos, len: r.length } : null;

  // Re-scan setelah replace
  findMatches(q);

  if (matches.length === 0) {
    activeQuery = '';
    currentMatchIdx = -1;
    replacedRanges = pendingRange ? [pendingRange] : [];
    // Otomatis isi CARI dengan teks hasil replace — siap cari lagi
    searchInput.value = r;
    searchHint.textContent = 'Diganti! Ubah kata cari atau tekan Cari lagi.';
    searchHint.className = 'ok';
    navCounter.textContent = '—';
    sbMatch.textContent = '0 tersisa';
    fullUpdate();
    return;
  }

  const afterPos = pos + r.length;
  let nextIdx = matches.findIndex(m => m >= afterPos);
  if (nextIdx === -1) nextIdx = 0;
  currentMatchIdx = nextIdx;
  activeQuery = q;
  replacedRanges = pendingRange ? [pendingRange] : [];

  navCounter.textContent = `${currentMatchIdx + 1}/${matches.length}`;
  sbMatch.textContent = `${currentMatchIdx + 1}/${matches.length}`;
  sbNav.classList.add('visible');
  searchHint.textContent = `${matches.length} match tersisa.`;
  searchHint.className = 'ok';

  // fullUpdate handles resize + renderHighlight sekaligus
  fullUpdate();
  scrollToMatch(currentMatchIdx);
}

function replaceAll() {
  const q = searchInput.value.trim();
  if (!q) return;
  const r = replaceInp.value;
  const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const regex = new RegExp(escaped, 'gi');
  const count = (textarea.value.match(regex) || []).length;
  if (!count) return;

  // Hitung posisi hasil replace di string baru (akumulasi shift per match)
  const newRanges = [];
  if (r.length > 0) {
    let src = textarea.value;
    let offset = 0;
    const re2 = new RegExp(escaped, 'gi');
    let m;
    while ((m = re2.exec(src)) !== null) {
      const newPos = m.index + offset;
      newRanges.push({ pos: newPos, len: r.length });
      offset += (r.length - q.length); // akumulasi shift setelah setiap replace
    }
  }

  isProgrammaticChange = true;
  textarea.value = textarea.value.replace(regex, r);
  isProgrammaticChange = false;
  pushHistory(textarea.value);
  // clearSearch dulu, lalu set replacedRanges (urutan penting!)
  clearSearch();
  replacedRanges = newRanges;
  activeQuery = q;
  closePopup();
  fullUpdate();
  sbMatch.textContent = `${count} diganti`;
  sbNav.classList.add('visible');
  setTimeout(() => { if (!matches.length) { sbNav.classList.remove('visible'); } }, 2000);
}

// ── EXIT APP ──
function exitApp() {
  isDirty = false;
  // Jika berjalan di dalam Android Wrapper
  if (window.AndroidBridge && window.AndroidBridge.exitApp) {
    window.AndroidBridge.exitApp();
    return;
  }
  // Coba tutup window (berfungsi di PWA standalone & wrapper)
  window.close();
  // Fallback jika window.close() diblokir browser
  setTimeout(() => {
    if (window.history.length > 1) {
      window.history.back();
    } else {
      // Tampilkan pesan "sudah ditutup"
      document.body.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100dvh;background:#0d0d0d;color:#71717a;font-family:Inter,sans-serif;font-size:15px;text-align:center;padding:24px;">Aplikasi ditutup.<br>Anda bisa menutup tab ini.</div>';
    }
  }, 300);
}

// ── LOAD FILE CONTENT (dari intent/share/URL) ──
function loadFileContent(content, name) {
  closeImageViewer();
  closePreview();
  textarea.value = content;
  fileNameInp.value = name || 'untitled.txt';
  updatePreviewButton();
  textarea.selectionStart = textarea.selectionEnd = 0;
  lastSavedContent = content;
  isDirty = false;
  undoStack = [{ val: content, pos: 0 }];
  redoStack = [];
  updateUndoRedoBtns();
  fullUpdate();
  scrollCont.scrollTop = 0;
}

// Kontrak JSON dari native (aman terhadap backtick/backslash/Unicode).
window.__voidLoadFile = function (payload) {
  remoteFileHint = payload.remotePath || null;
  const displayName = remoteFileHint ? (payload.name || remoteFileHint.split('/').pop()) : payload.name;
  loadFileContent(payload.content || '', displayName);
};

/* ══════════ PREVIEW HTML / MARKDOWN (Fitur D.2 & D.3) ══════════ */
const previewOverlay = document.getElementById('preview-overlay');
const previewFrame = document.getElementById('preview-frame');
const btnPreview = document.getElementById('btn-preview');

function fileExt(name) { return (name || '').split('.').pop().toLowerCase(); }

// Hanya .html/.htm/.md yang bisa dipreview. CSS/JS berdiri sendiri tidak bisa dirender.
function isPreviewable(name) { return ['html', 'htm', 'md', 'markdown'].includes(fileExt(name)); }

function updatePreviewButton() {
  btnPreview.classList.toggle('available', isPreviewable(fileNameInp.value.trim()));
}

const MARKDOWN_CSS = `
  :root { color-scheme: light }
  body { margin: 0; padding: 20px 18px 48px; font: 15px/1.65 -apple-system, "Segoe UI", Roboto, sans-serif; color: #1f2328; background: #fff; overflow-wrap: anywhere; }
  h1, h2 { border-bottom: 1px solid #d8dee4; padding-bottom: .3em; }
  h1, h2, h3, h4 { margin: 1.4em 0 .6em; line-height: 1.3; }
  p, ul, ol, blockquote, table, pre { margin: 0 0 1em; }
  code { font: 13px/1.5 ui-monospace, "JetBrains Mono", monospace; background: #eff1f3; padding: .15em .4em; border-radius: 5px; }
  pre { background: #f6f8fa; padding: 14px; border-radius: 8px; overflow-x: auto; }
  pre code { background: none; padding: 0; }
  blockquote { border-left: 4px solid #d0d7de; color: #59636e; padding: 0 1em; }
  table { border-collapse: collapse; display: block; overflow-x: auto; }
  th, td { border: 1px solid #d0d7de; padding: 6px 12px; }
  img { max-width: 100%; }
  a { color: #0969da; }
`;

function buildPreviewDocument(name, source) {
  if (['md', 'markdown'].includes(fileExt(name))) {
    let body;
    if (window.marked && typeof window.marked.parse === 'function') {
      body = window.marked.parse(source, { gfm: true, breaks: false });
    } else {
      // Fallback aman bila bundle marked gagal dimuat: tampilkan sebagai teks polos.
      body = '<pre>' + escHtml(source) + '</pre>';
    }
    return `<!doctype html><html lang="id"><head><meta charset="utf-8">` +
      `<meta name="viewport" content="width=device-width,initial-scale=1">` +
      `<style>${MARKDOWN_CSS}</style></head><body>${body}</body></html>`;
  }
  return source;
}

function openPreview() {
  const name = fileNameInp.value.trim() || 'untitled.txt';
  if (!isPreviewable(name)) { sftpToast('Preview hanya untuk file HTML atau Markdown'); return; }
  // Render isi editor SAAT INI (belum tentu sudah disimpan), sepenuhnya offline.
  previewFrame.srcdoc = buildPreviewDocument(name, textarea.value);
  document.getElementById('preview-name').textContent = name;
  previewOverlay.classList.add('open');
}

function closePreview() {
  if (!previewOverlay.classList.contains('open')) return;
  previewOverlay.classList.remove('open');
  previewFrame.srcdoc = '';
}

/* ══════════ AUTO-VIEWER GAMBAR (Fitur D.1) ══════════ */
const imageOverlay = document.getElementById('image-overlay');
const imageView = document.getElementById('image-view');

// Dipanggil native saat file gambar dibuka (SFTP, bookmark folder, atau Select document).
window.__voidLoadImage = function (payload) {
  // Native selalu mengosongkan activeRemotePath untuk gambar (hanya-baca) — cermin ikut kosong.
  remoteFileHint = null;
  const name = payload.name || 'gambar';
  imageView.src = `data:${payload.mime || 'image/png'};base64,${payload.base64}`;
  imageView.alt = name;
  document.getElementById('image-name').textContent = name;
  const bytes = Math.round((payload.base64 || '').length * 3 / 4);
  document.getElementById('image-meta').textContent =
    `${payload.mime || 'image'} · ${formatBytes(bytes)} · hanya-baca (gambar tidak memiliki mode edit teks)`;
  imageOverlay.classList.add('open');
  closeSftpPanel();
};

function closeImageViewer() {
  if (!imageOverlay.classList.contains('open')) return;
  imageOverlay.classList.remove('open');
  imageView.removeAttribute('src');
}

function isImageName(name) {
  return ['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp', 'svg', 'ico'].includes(fileExt(name));
}

/**
 * requestId TETAP yang dipakai MainActivity untuk melaporkan hasil Save — sama untuk
 * SFTP, file lokal, dan "Simpan sebagai". Semua jalur wajib melapor, jadi tidak ada lagi
 * Save yang gagal tanpa jejak (dulu hanya jalur remote yang punya handler).
 */
const SAVE_REQUEST_ID = 'save-file';

// ── SAVE FILE (helper) ── mengembalikan Promise yang settle setelah hasil nyata diterima.
function performSave() {
  const name = fileNameInp.value.trim() || 'untitled.txt';

  // Jika berjalan di dalam Android Wrapper
  if (window.AndroidBridge && window.AndroidBridge.onSaveRequest) {
    const snapshot = textarea.value;
    // Sesi Save sebelumnya (mis. dialog "Simpan sebagai" ditinggalkan) dibuang dulu.
    pendingRequests.delete(SAVE_REQUEST_ID);
    const done = new Promise((resolve) => {
      pendingRequests.set(SAVE_REQUEST_ID, {
        resolve: (data) => {
          // Hanya tandai bersih bila isi editor belum berubah lagi sejak permintaan tadi.
          if (textarea.value === snapshot) { isDirty = false; lastSavedContent = snapshot; }
          sftpToast((data && data.target === 'remote') ? 'Tersimpan ke server' : 'Tersimpan');
          resolve(true);
        },
        reject: (err) => {
          isDirty = true;
          sftpToast('Gagal simpan: ' + err.message);
          resolve(false);
        }
      });
    });
    try {
      window.AndroidBridge.onSaveRequest(snapshot, name);
    } catch (err) {
      pendingRequests.delete(SAVE_REQUEST_ID);
      sftpToast('Gagal simpan: jembatan Android tidak merespons');
      return Promise.resolve(false);
    }
    return done;
  }

  const blob = new Blob([textarea.value], { type: 'text/plain' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
  isDirty = false;
  lastSavedContent = textarea.value;
  return Promise.resolve(true);
}

// ── SAVE FILE (manual — double confirmation) ──
async function saveFile() {
  // Konfirmasi pertama
  const wantSave = await showConfirm(
    'Apakah anda akan menyimpan perubahan?', 'Simpan', 'Batal'
  );
  if (wantSave !== 'ok') return false;

  // Konfirmasi kedua (yakin)
  const sureSave = await showConfirm(
    'Apakah anda sudah yakin akan menyimpan perubahan?', 'Iya', 'Batal'
  );
  if (sureSave !== 'ok') return false;

  return performSave();
}

// ── RESIZE OBSERVER ──
let resizeTimer;
const ro = new ResizeObserver(() => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => {
    resizeTextarea();
    syncGutterScroll();
  }, 100);
});
ro.observe(scrollCont);

// ── CONFIRM DIALOG SYSTEM ──
const confirmDialog = document.getElementById('confirm-dialog');
const confirmMessageEl = document.getElementById('confirm-message');
const confirmCancelBtn = document.getElementById('confirm-cancel');
const confirmOkBtn = document.getElementById('confirm-ok');
const confirmDiscardBtn = document.getElementById('confirm-discard');
const confirmBtnsContainer = document.getElementById('confirm-btns');
let confirmResolve = null;

/**
 * Selesaikan sesi konfirmasi tepat SATU kali. Tombol memanggil ini SEBELUM dialog.close()
 * karena di sebagian versi WebView event 'close' dikirim sinkron — dulu handler 'close'
 * sempat menyelesaikan promise dengan 'cancel' lebih dulu sehingga Save selalu dibatalkan
 * tanpa pesan apa pun (regresi "Save rusak total", SFTP maupun lokal).
 */
function settleConfirm(value) {
  const resolve = confirmResolve;
  confirmResolve = null;
  if (resolve) resolve(value);
}

function showConfirm(message, okText = 'Simpan', cancelText = 'Batal', discardText = null) {
  // Sesi lama yang belum selesai (mis. dialog dibuka ulang) dibatalkan eksplisit.
  settleConfirm('cancel');
  return new Promise((resolve) => {
    confirmMessageEl.textContent = message;
    confirmOkBtn.textContent = okText;
    confirmCancelBtn.textContent = cancelText;
    if (discardText) {
      confirmDiscardBtn.textContent = discardText;
      confirmDiscardBtn.style.display = '';
      confirmBtnsContainer.classList.add('three-buttons');
    } else {
      confirmDiscardBtn.style.display = 'none';
      confirmBtnsContainer.classList.remove('three-buttons');
    }
    confirmResolve = resolve;
    if (!confirmDialog.open) confirmDialog.showModal();
  });
}

// PENTING: settle dulu, close belakangan (lihat komentar settleConfirm).
confirmOkBtn.addEventListener('click', () => {
  settleConfirm('ok');
  confirmDialog.close();
});

confirmCancelBtn.addEventListener('click', () => {
  settleConfirm('cancel');
  confirmDialog.close();
});

confirmDiscardBtn.addEventListener('click', () => {
  settleConfirm('discard');
  confirmDialog.close();
});

// Handle Escape key (fires 'close' after 'cancel').
// PENTING: event 'close' dari dialog.close() dikirim sebagai task TERTUNDA. Pada alur
// konfirmasi ganda (saveFile), dialog kedua sudah terbuka saat event 'close' dialog
// pertama baru tiba — tanpa guard ini, resolver dialog kedua langsung di-resolve
// 'cancel' sehingga Save selalu batal diam-diam (regresi Save total SFTP & lokal).
confirmDialog.addEventListener('close', () => {
  if (confirmDialog.open) return; // dialog sudah dibuka ulang — event ini milik sesi lama
  settleConfirm('cancel');       // no-op bila tombol sudah menyelesaikan sesi ini
});

// Fallback: closedby not supported — prevent backdrop click from closing
if (!('closedBy' in HTMLDialogElement.prototype)) {
  confirmDialog.addEventListener('click', (event) => {
    if (event.target !== confirmDialog) return;
    const rect = confirmDialog.getBoundingClientRect();
    const inside = (
      rect.top <= event.clientY && event.clientY <= rect.top + rect.height &&
      rect.left <= event.clientX && event.clientX <= rect.left + rect.width
    );
    if (!inside) {
      // Backdrop click — treat as cancel
      confirmDialog.close();
    }
  });
}

// ── EXIT PREVENTION ──
// Browser close / tab close / refresh → native browser dialog
window.addEventListener('beforeunload', (e) => {
  if (isDirty) {
    e.preventDefault();
    e.returnValue = '';
  }
});

// Back button interception (especially for mobile)
history.pushState({ voidedit: true }, '');
let exitDialogActive = false;

// Konfirmasi keluar saat masih ada perubahan belum tersimpan.
async function confirmExitWithUnsavedChanges() {
  if (exitDialogActive) return;
  exitDialogActive = true;
  const result = await showConfirm(
    'Anda memiliki perubahan yang belum disimpan.',
    'Simpan Perubahan',
    'Batal',
    'Keluar Tanpa Simpan'
  );
  exitDialogActive = false;

  if (result === 'ok') {
    // Tunggu hasil tulis sebenarnya: dulu exitApp() langsung menutup Activity sehingga
    // penulisan SFTP yang masih berjalan ikut mati dan perubahan hilang.
    const saved = await performSave();
    if (!saved) return; // gagal → user tetap di editor, perubahan tidak dibuang
    exitApp();
  } else if (result === 'discard') {
    exitApp();
  }
  // 'cancel' → user tetap di editor
}

window.addEventListener('popstate', () => {
  if (!isDirty) return; // no unsaved changes, allow navigation
  // Block navigation by pushing state back
  history.pushState({ voidedit: true }, '');
  confirmExitWithUnsavedChanges();
});

/**
 * Dipanggil MainActivity.onBackPressed. Mengembalikan true bila tombol Back sudah
 * ditangani oleh UI web (menutup dialog/overlay/panel), false bila aplikasi boleh keluar.
 * WebView ini SPA satu halaman, jadi webView.canGoBack() bukan indikator yang benar.
 */
let backErrorStreak = 0;
window.__voidHandleBack = function () {
  try {
    // Dialog terakhir di DOM yang terbuka = yang paling atas (dialog bisa bertumpuk,
    // mis. konfirmasi Simpan di atas dialog item explorer).
    const openDialogs = document.querySelectorAll('dialog[open]');
    if (openDialogs.length) {
      openDialogs[openDialogs.length - 1].close();
      backErrorStreak = 0;
      return true;
    }
    if (imageOverlay.classList.contains('open')) { closeImageViewer(); backErrorStreak = 0; return true; }
    if (previewOverlay.classList.contains('open')) { closePreview(); backErrorStreak = 0; return true; }
    // Menu "tambah" di explorer adalah popover ringan: tutup dulu sebelum navigasi.
    if (sftpActionMenu && sftpActionMenu.classList.contains('open')) { closeSftpActions(); backErrorStreak = 0; return true; }
    if (localActionMenu && localActionMenu.classList.contains('open')) { closeLocalActions(); backErrorStreak = 0; return true; }
    // Panel Explorer: naik satu level folder / kembali ke hub. Koneksi SFTP TIDAK diputus,
    // jadi Back tidak pernah memaksa user reconnect.
    if (sftpPanel.classList.contains('open')) { explorerBack(); backErrorStreak = 0; return true; }
    if (isDirty) { confirmExitWithUnsavedChanges(); backErrorStreak = 0; return true; }
  } catch (err) {
    // Error sekali (mis. elemen belum siap) tidak boleh langsung menutup aplikasi, tetapi
    // error yang terus berulang juga tidak boleh mengunci tombol Back selamanya.
    backErrorStreak += 1;
    return backErrorStreak < 2;
  }
  backErrorStreak = 0;
  return false;
};

// ── INIT ──
lastSavedContent = INITIAL;
initSettings();

fullUpdate();
updatePreviewButton();
fileNameInp.addEventListener('input', updatePreviewButton);

// ── RECEIVE FILE FROM EXTERNAL (intent/share/URL) ──
// File Handling API — saat app dibuka via "Buka dengan" (PWA)
if ('launchQueue' in window) {
  launchQueue.setConsumer(async (launchParams) => {
    if (launchParams.files && launchParams.files.length > 0) {
      const fileHandle = launchParams.files[0];
      const file = await fileHandle.getFile();
      const content = await file.text();
      loadFileContent(content, file.name);
    }
  });
}

// URL parameter support — wrapper bisa passing via ?file=<url>&name=<filename>
// atau ?content=<encoded_content>&name=<filename>
(function handleExternalFile() {
  const params = new URLSearchParams(window.location.search);

  // Mode 1: file URL → fetch isinya
  const fileUrl = params.get('file') || params.get('url');
  if (fileUrl) {
    fetch(fileUrl)
      .then(r => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.text();
      })
      .then(content => {
        const name = params.get('name') || decodeURIComponent(fileUrl.split('/').pop()) || 'untitled.txt';
        loadFileContent(content, name);
      })
      .catch(err => console.error('Gagal memuat file:', err));
    return;
  }

  // Mode 2: konten langsung di URL param (dari Share Target GET)
  const contentParam = params.get('content') || params.get('text');
  if (contentParam) {
    const name = params.get('name') || params.get('title') || 'untitled.txt';
    loadFileContent(contentParam, name);
    return;
  }

  // Mode 3: intent data di URL hash (beberapa wrapper Android)
  if (window.location.hash && window.location.hash.length > 1) {
    try {
      const hashData = JSON.parse(decodeURIComponent(window.location.hash.slice(1)));
      if (hashData.content) {
        loadFileContent(hashData.content, hashData.name || 'untitled.txt');
      }
    } catch(e) { /* bukan JSON, abaikan */ }
  }
})();

/* ═══════════════ SFTP EXPLORER ═══════════════ */
const bridge = window.AndroidBridge;
const sftpPanel = document.getElementById('sftp-panel');
const sftpConnectView = document.getElementById('sftp-connect-view');
const sftpBrowser = document.getElementById('sftp-browser');
const sftpListEl = document.getElementById('sftp-list');
const sftpBreadcrumb = document.getElementById('sftp-breadcrumb');
const sftpPathLabel = document.getElementById('sftp-path-label');
const sftpServerLabel = document.getElementById('sftp-server-label');
const sftpActionMenu = document.getElementById('sftp-action-menu');
const localActionMenu = document.getElementById('local-action-menu');
const sftpProgressEl = document.getElementById('sftp-progress');
const sftpToastEl = document.getElementById('sftp-toast');

let sftpConnected = false;
let currentDir = '/';
let sftpHomeDir = '/';   // titik awal browsing (home server) — batas "naik 1 level" tombol Back
let selectedEntry = null;
let selectedEntrySource = 'sftp';
let pendingConnectConfig = null;    // dipakai saat konfirmasi host key
let pendingCreateKind = null;       // 'file' | 'folder'
const pendingRequests = new Map();  // requestId -> {resolve, reject}
let requestSeq = 0;

const settings = { sortAscending: true, showHidden: false, autoList: true };

function hasBridge() { return !!(bridge && bridge.sftpConnect); }

// Preferensi explorer dibaca sekali saat startup (bukan hanya saat panel dibuka), supaya
// listing pertama — SFTP maupun folder lokal — sudah memakai nilai toggle yang benar.
loadSftpSettings();

function sftpToast(message) {
  sftpToastEl.textContent = message;
  sftpToastEl.classList.add('visible');
  clearTimeout(sftpToast._t);
  sftpToast._t = setTimeout(() => sftpToastEl.classList.remove('visible'), 2600);
}

// Promise-based bridge call. requestId tetap untuk connect (save-remote khusus).
function callBridge(fixedId, invoke) {
  return new Promise((resolve, reject) => {
    const requestId = fixedId || ('req-' + (++requestSeq) + '-' + Date.now());
    pendingRequests.set(requestId, { resolve, reject });
    try { invoke(requestId); }
    catch (err) { pendingRequests.delete(requestId); reject(err); }
  });
}

window.onSftpResult = function (payload) {
  const pending = pendingRequests.get(payload.requestId);
  if (!pending) return;
  pendingRequests.delete(payload.requestId);
  if (payload.success) pending.resolve(payload.data);
  else pending.reject(new Error(payload.error || 'Operasi gagal'));
};

window.onSftpProgress = function (payload) {
  sftpProgressEl.classList.add('visible');
  sftpProgressEl.textContent = `Mengunggah ${payload.done}/${payload.total} — ${payload.label}`;
  if (payload.done >= payload.total) setTimeout(() => sftpProgressEl.classList.remove('visible'), 1200);
};

function openSftpPanel() {
  if (!hasBridge()) { sftpToast('Penjelajah Berkas hanya tersedia di aplikasi Android'); return; }
  sftpPanel.classList.add('open');
  loadSftpSettings();
  if (sftpConnected) showExplorerView('sftp');
  else { showExplorerView('home'); renderExplorerHome(); }
}
function closeSftpPanel() { sftpPanel.classList.remove('open'); closeSftpActions(); closeLocalActions(); }

/* ══════════ HUB PENJELAJAH BERKAS (Fitur A / C / E) ══════════ */
const explorerHome = document.getElementById('explorer-home');
const localBrowser = document.getElementById('local-browser');
const explorerTitle = document.getElementById('explorer-title');

let explorerView = 'home';          // 'home' | 'connect' | 'sftp' | 'local'
let editingConnectionId = null;     // non-null = form dalam mode Edit
let selectedSaved = null;           // koneksi tersimpan yang di-long-press
let selectedBookmark = null;        // bookmark folder yang di-long-press
let labelTarget = null;             // {kind:'saved'|'bookmark', id}
let pendingTreeUri = null;          // hasil tree picker untuk dialog "Tambah jalur"
let localStack = [];                // riwayat navigasi folder lokal
let localBookmarkLabel = '';

function showExplorerView(view) {
  explorerView = view;
  explorerHome.classList.toggle('visible', view === 'home');
  sftpConnectView.style.display = view === 'connect' ? '' : 'none';
  sftpBrowser.classList.toggle('visible', view === 'sftp');
  localBrowser.classList.toggle('visible', view === 'local');
  if (view === 'home') {
    // Keluar dari form koneksi selalu membatalkan mode Edit yang tertunda.
    editingConnectionId = null;
    explorerTitle.textContent = 'Penjelajah Berkas';
    sftpServerLabel.textContent = sftpConnected ? 'SFTP aktif' : 'Pilih lokasi berkas';
  } else if (view === 'local') {
    explorerTitle.textContent = localBookmarkLabel || 'Folder lokal';
  } else {
    explorerTitle.textContent = view === 'connect' ? 'Koneksi SFTP' : 'SFTP Explorer';
  }
  closeSftpActions();
  closeLocalActions();
}

// Naikkan path satu level: '/var/www/html' → '/var/www'. Root ('/') tetap '/'.
function parentDirOf(path) {
  const trimmed = (path || '/').replace(/\/+$/, '');
  const idx = trimmed.lastIndexOf('/');
  return idx <= 0 ? '/' : trimmed.slice(0, idx);
}

// Tombol ‹ di header / tombol Back Android: dari sub-view kembali ke hub, dari hub tutup panel.
// Di browser SFTP, Back naik 1 level folder dulu (pakai koneksi yang masih terbuka, tanpa
// reconnect); baru setelah di root/home keluar ke hub. Sesi SFTP TIDAK diputus.
function explorerBack() {
  if (explorerView === 'local' && localStack.length > 1) { localGoUp(); return; }
  if (explorerView === 'sftp' && sftpConnected) {
    const atRoot = currentDir === '/' || currentDir === sftpHomeDir;
    if (!atRoot) { navigateTo(parentDirOf(currentDir)); return; }
  }
  if (explorerView === 'home') { closeSftpPanel(); return; }
  showExplorerView('home');
  renderExplorerHome();
}

/**
 * Tap = onTap, long-press 500ms = onLongPress. Timer dibatalkan saat scroll/gerak
 * agar scrolling daftar tidak memicu menu. Versi generik dari attachRowGestures.
 */
function attachGestures(el, onTap, onLongPress) {
  let timer = null; let longPressed = false; let startY = 0;
  const cancel = () => { clearTimeout(timer); timer = null; };
  el.addEventListener('pointerdown', (e) => {
    longPressed = false; startY = e.clientY;
    if (!onLongPress) return;
    timer = setTimeout(() => { longPressed = true; onLongPress(); }, 500);
  });
  el.addEventListener('pointermove', (e) => { if (Math.abs(e.clientY - startY) > 10) cancel(); });
  el.addEventListener('pointerup', cancel);
  el.addEventListener('pointercancel', cancel);
  el.addEventListener('click', () => {
    if (longPressed) { longPressed = false; return; }
    if (onTap) onTap();
  });
}

function makeRow({ icon, iconClass, title, meta, badge, onTap, onLongPress }) {
  const row = document.createElement('button');
  row.type = 'button'; row.className = 'file-row'; row.setAttribute('role', 'listitem');
  const iconEl = document.createElement('div');
  iconEl.className = 'file-icon' + (iconClass ? ' ' + iconClass : '');
  iconEl.textContent = icon;
  const info = document.createElement('div'); info.className = 'file-info';
  const name = document.createElement('span'); name.className = 'file-name'; name.textContent = title;
  const metaEl = document.createElement('span'); metaEl.className = 'file-meta'; metaEl.textContent = meta || '';
  info.append(name, metaEl);
  row.append(iconEl, info);
  if (badge) {
    const badgeEl = document.createElement('span');
    badgeEl.className = 'badge' + (badge === 'FOLDER' ? ' local' : '');
    badgeEl.textContent = badge;
    row.appendChild(badgeEl);
  }
  attachGestures(row, onTap, onLongPress);
  return row;
}

function renderExplorerHome() {
  const actions = document.getElementById('explorer-actions');
  actions.innerHTML = '';
  actions.append(
    makeRow({ icon: '+', iconClass: 'action', title: 'Tambah jalur', meta: 'Bookmark folder di penyimpanan perangkat', onTap: openAddPathDialog }),
    makeRow({ icon: 'SFTP', iconClass: 'action', title: 'Tambahkan SFTP', meta: 'Sambungkan server baru', onTap: openConnectForm }),
    makeRow({ icon: 'DOC', iconClass: 'action', title: 'Select document', meta: 'Pilih satu file lalu buka di editor', onTap: selectDocument })
  );
  renderSavedConnections();
  renderBookmarks();
}

/* ── Fitur A: daftar koneksi SFTP tersimpan ── */
function renderSavedConnections() {
  const list = document.getElementById('explorer-saved');
  list.innerHTML = '';
  let payload = { available: false, items: [] };
  try { payload = JSON.parse(bridge.savedConnections()); } catch (_) {}
  if (!payload.available) {
    list.appendChild(emptyNote('Penyimpanan terenkripsi tidak tersedia di perangkat ini. Silakan hubungkan server secara manual.'));
    return;
  }
  const items = payload.items || [];
  if (!items.length) { list.appendChild(emptyNote('Belum ada koneksi tersimpan.')); return; }
  items.forEach((item) => {
    list.appendChild(makeRow({
      icon: 'DIR',
      title: item.label,
      meta: `${item.username}@${item.host}:${item.port} · ${item.authType === 'key' ? 'private key' : 'password'}`,
      badge: 'SFTP',
      onTap: () => connectSaved(item),
      onLongPress: () => openSavedMenu(item)
    }));
  });
}

/* ── Fitur E: daftar bookmark folder lokal ── */
function renderBookmarks() {
  const list = document.getElementById('explorer-bookmarks');
  list.innerHTML = '';
  let items = [];
  try { items = JSON.parse(bridge.localBookmarks()) || []; } catch (_) {}
  if (!items.length) { list.appendChild(emptyNote('Belum ada folder. Gunakan "Tambah jalur".')); return; }
  items.forEach((item) => {
    list.appendChild(makeRow({
      icon: 'DIR',
      title: item.label,
      meta: decodeURIComponent(item.treeUri),
      badge: 'FOLDER',
      onTap: () => openBookmark(item),
      onLongPress: () => openBookmarkMenu(item)
    }));
  });
}

function emptyNote(text) {
  const note = document.createElement('div');
  note.className = 'hub-empty'; note.textContent = text;
  return note;
}

/* ── Form koneksi: mode tambah & mode edit ── */
function openConnectForm(saved) {
  editingConnectionId = saved ? saved.id : null;
  const isEdit = !!saved;
  document.getElementById('sftp-form-title').textContent = isEdit ? 'Edit koneksi' : 'Akses file server';
  document.getElementById('sftp-form-desc').textContent = isEdit
    ? 'Ubah data koneksi lalu simpan. Password disimpan terenkripsi di perangkat.'
    : 'Sambungkan server melalui SFTP. Fingerprint host selalu diverifikasi sebelum kredensial dikirim.';
  document.getElementById('sftp-host').value = saved ? saved.host : '';
  document.getElementById('sftp-port').value = saved ? saved.port : 22;
  document.getElementById('sftp-user').value = saved ? saved.username : '';
  document.getElementById('sftp-auth').value = saved ? saved.authType : 'password';
  document.getElementById('sftp-password').value = saved ? (saved.password || '') : '';
  document.getElementById('sftp-key-path').value = saved ? (saved.privateKeyPath || '') : '';
  document.getElementById('sftp-passphrase').value = saved ? (saved.passphrase || '') : '';
  document.getElementById('sftp-key-button').textContent =
    saved && saved.privateKeyPath ? saved.privateKeyPath.split('/').pop() : 'Pilih private key';
  document.getElementById('sftp-label').value = saved ? saved.label : '';
  document.getElementById('sftp-remember-row').style.display = isEdit ? 'none' : '';
  document.getElementById('sftp-connect-button').textContent = isEdit ? 'Simpan Perubahan' : 'Hubungkan';
  toggleSftpAuth();
  showExplorerView('connect');
}

/* ── Fitur C: Select document ── */
function selectDocument() {
  if (!bridge.pickLocalDocument) { sftpToast('Fitur ini butuh versi aplikasi terbaru'); return; }
  bridge.pickLocalDocument();
  closeSftpPanel();
}

/**
 * Pembungkus @JavascriptInterface sinkron. Semua method wrapSync di MainActivity
 * mengembalikan JSON {ok:true,...} atau {ok:false,error}. Melempar Error bila gagal.
 */
function callSync(invoke, label) {
  let result;
  try { result = JSON.parse(invoke()); }
  catch (_) { throw new Error(label + ' gagal: penyimpanan tidak merespons'); }
  if (!result || !result.ok) throw new Error((result && result.error) || (label + ' gagal'));
  return result;
}

/* ══════════ FITUR A — koneksi SFTP tersimpan ══════════ */

// Koneksi tersimpan yang sedang menunggu konfirmasi fingerprint host.
let pendingSavedConnect = null;

// Tap baris koneksi tersimpan: kredensial dibaca di sisi native, tidak pernah masuk WebView.
async function connectSaved(item) {
  pendingConnectConfig = null;
  pendingSavedConnect = { id: item.id, label: item.label };
  sftpToast('Menghubungkan ke ' + item.label + '…');
  try {
    const data = await callBridge('conn-' + Date.now(), (id) => bridge.sftpConnectSaved(id, item.id));
    if (data.status === 'hostKey') {
      pendingSavedConnect.fingerprint = data.fingerprint;
      document.getElementById('sftp-fingerprint').textContent = data.fingerprint;
      document.getElementById('sftp-hostkey-dialog').showModal();
      return;
    }
    pendingSavedConnect = null;
    onConnected(null, data.home || '/', { label: data.label || item.label });
  } catch (err) {
    pendingSavedConnect = null;
    sftpToast(err.message);
  }
}

function openSavedMenu(item) {
  selectedSaved = item;
  document.getElementById('saved-item-title').textContent = item.label;
  document.getElementById('saved-item-sub').textContent =
    `${item.username}@${item.host}:${item.port}`;
  document.getElementById('saved-item-dialog').showModal();
}

function renameSavedConnection() {
  document.getElementById('saved-item-dialog').close();
  if (!selectedSaved) return;
  openLabelDialog('saved', selectedSaved.id, selectedSaved.label);
}

// Mode edit memerlukan kredensial, jadi diambil lewat savedConnectionDetail (sekali pakai).
function editSavedConnection() {
  document.getElementById('saved-item-dialog').close();
  if (!selectedSaved) return;
  let detail = null;
  try { detail = JSON.parse(bridge.savedConnectionDetail(selectedSaved.id)); } catch (_) {}
  if (!detail || !detail.found) { sftpToast('Koneksi tersimpan tidak ditemukan'); return; }
  openConnectForm(detail);
}

async function deleteSavedConnection() {
  document.getElementById('saved-item-dialog').close();
  if (!selectedSaved) return;
  const target = selectedSaved;
  const ok = await showConfirm(`Hapus koneksi "${target.label}"?`, 'Hapus', 'Batal');
  if (ok !== 'ok') return;
  try {
    callSync(() => bridge.deleteConnection(target.id), 'Hapus koneksi');
    sftpToast('Koneksi dihapus');
  } catch (err) { sftpToast(err.message); }
  selectedSaved = null;
  renderSavedConnections();
}

// Simpan koneksi baru setelah connect berhasil, bila checkbox "Simpan koneksi" aktif.
function persistConnection(config) {
  const remember = document.getElementById('sftp-remember');
  if (!remember || !remember.checked || !bridge.saveConnection) return;
  const label = document.getElementById('sftp-label').value.trim();
  try {
    callSync(() => bridge.saveConnection(JSON.stringify(config), label), 'Simpan koneksi');
  } catch (err) {
    // Koneksi tetap aktif walaupun penyimpanan gagal — cukup beri tahu user.
    sftpToast(err.message);
  }
}

/* ══════════ FITUR E — bookmark folder lokal (SAF tree) ══════════ */

function openAddPathDialog() {
  if (!bridge.pickFolderTree) { sftpToast('Fitur ini butuh versi aplikasi terbaru'); return; }
  pendingTreeUri = null;
  document.getElementById('addpath-uri').value = '';
  document.getElementById('addpath-name').value = '';
  document.getElementById('addpath-dialog').showModal();
}

// Membuka ACTION_OPEN_DOCUMENT_TREE. Izin dipertahankan native agar tetap terbaca
// setelah aplikasi di-restart.
async function pickFolderTree() {
  try {
    const data = await callBridge(null, (id) => bridge.pickFolderTree(id));
    pendingTreeUri = data.treeUri;
    document.getElementById('addpath-uri').value = decodeURIComponent(data.treeUri);
    const nameInput = document.getElementById('addpath-name');
    if (!nameInput.value.trim()) nameInput.value = data.name || 'Folder';
  } catch (err) { sftpToast(err.message); }
}

function submitAddPath(event) {
  event.preventDefault();
  if (!pendingTreeUri) { sftpToast('Pilih folder terlebih dahulu'); return; }
  const label = document.getElementById('addpath-name').value.trim();
  try {
    callSync(() => bridge.addLocalBookmark(pendingTreeUri, label), 'Tambah folder');
    document.getElementById('addpath-dialog').close();
    pendingTreeUri = null;
    sftpToast('Folder ditambahkan');
    renderBookmarks();
  } catch (err) { sftpToast(err.message); }
}

function openBookmarkMenu(item) {
  selectedBookmark = item;
  document.getElementById('bookmark-item-title').textContent = item.label;
  document.getElementById('bookmark-item-sub').textContent = decodeURIComponent(item.treeUri);
  document.getElementById('bookmark-item-dialog').showModal();
}

function renameBookmark() {
  document.getElementById('bookmark-item-dialog').close();
  if (!selectedBookmark) return;
  openLabelDialog('bookmark', selectedBookmark.id, selectedBookmark.label);
}

async function deleteBookmark() {
  document.getElementById('bookmark-item-dialog').close();
  if (!selectedBookmark) return;
  const target = selectedBookmark;
  const ok = await showConfirm(
    `Hapus folder "${target.label}" dari daftar? File di perangkat tidak dihapus.`, 'Hapus', 'Batal'
  );
  if (ok !== 'ok') return;
  try {
    callSync(() => bridge.deleteLocalBookmark(target.id), 'Hapus folder');
    sftpToast('Folder dihapus dari daftar');
  } catch (err) { sftpToast(err.message); }
  selectedBookmark = null;
  renderBookmarks();
}

/* ── Dialog "Ubah Nama" dipakai bersama koneksi & bookmark ── */
function openLabelDialog(kind, id, current) {
  labelTarget = { kind, id };
  document.getElementById('label-dialog-title').textContent = 'Ubah Nama';
  const input = document.getElementById('label-dialog-value');
  input.value = current || '';
  document.getElementById('label-dialog').showModal();
  setTimeout(() => input.focus(), 50);
}

function submitLabel(event) {
  event.preventDefault();
  if (!labelTarget) return;
  const value = document.getElementById('label-dialog-value').value.trim();
  if (!value) { sftpToast('Nama tidak boleh kosong'); return; }
  const target = labelTarget;
  labelTarget = null;
  document.getElementById('label-dialog').close();
  try {
    if (target.kind === 'saved') {
      callSync(() => bridge.renameConnection(target.id, value), 'Ubah nama');
      renderSavedConnections();
    } else {
      callSync(() => bridge.renameLocalBookmark(target.id, value), 'Ubah nama');
      renderBookmarks();
    }
    sftpToast('Nama diubah');
  } catch (err) { sftpToast(err.message); }
}

/* ── Browser folder lokal ── */

// localStack menyimpan jejak navigasi: [{uri, label}, …]. Elemen terakhir = folder aktif.
function openBookmark(item) {
  localBookmarkLabel = item.label;
  localStack = [{ uri: item.treeUri, label: item.label }];
  showExplorerView('local');
  loadLocalList();
}

function localGoUp() {
  if (localStack.length > 1) {
    localStack.pop();
    loadLocalList();
    return;
  }
  showExplorerView('home');
  renderExplorerHome();
}

function refreshLocal() { if (localStack.length) loadLocalList(); }

function renderLocalState(message) {
  const list = document.getElementById('local-list');
  list.innerHTML = '';
  const box = document.createElement('div');
  box.className = 'state-box'; box.textContent = message;
  list.appendChild(box);
}

async function loadLocalList() {
  const current = localStack[localStack.length - 1];
  if (!current) { showExplorerView('home'); renderExplorerHome(); return; }
  document.getElementById('local-path-label').textContent =
    localStack.map((item) => item.label).join(' / ');
  renderLocalState('Memuat…');
  try {
    // showHidden dikirim eksplisit — nilai yang sama dipakai sftpList, jadi toggle
    // "tampilkan berkas tersembunyi" berlaku identik di folder bookmark lokal.
    const entries = await callBridge(null, (id) => bridge.localList(id, current.uri, settings.showHidden));
    renderLocalEntries(entries || []);
  } catch (err) {
    renderLocalState('Gagal memuat: ' + err.message);
  }
}

function renderLocalEntries(entries) {
  const list = document.getElementById('local-list');
  list.innerHTML = '';
  if (!entries.length) { renderLocalState('Folder kosong'); return; }
  entries.forEach((entry) => {
    const ext = (entry.name.split('.').pop() || '').slice(0, 3).toUpperCase();
    const isImg = !entry.directory && isImageName(entry.name);
    list.appendChild(makeRow({
      icon: entry.directory ? 'DIR' : (isImg ? 'IMG' : (ext || 'TXT')),
      title: entry.name,
      meta: entry.directory
        ? 'Folder'
        : `${formatBytes(entry.size)} · ${entry.modified ? new Date(entry.modified).toLocaleDateString('id-ID') : '—'}`,
      onTap: () => {
        if (entry.directory) {
          localStack.push({ uri: entry.uri, label: entry.name });
          loadLocalList();
        } else {
          openLocalFile(entry);
        }
      },
      onLongPress: () => openLocalItemMenu(entry)
    }));
  });
}

// Native memutuskan sendiri: teks → editor, gambar → viewer (Fitur D.1).
async function openLocalFile(entry) {
  sftpToast('Membuka ' + entry.name + '…');
  try {
    await callBridge(null, (id) => bridge.localOpen(id, entry.uri));
    closeSftpPanel();
  } catch (err) { sftpToast(err.message); }
}

function toggleLocalActions() { localActionMenu.classList.toggle('open'); }
function closeLocalActions() { localActionMenu.classList.remove('open'); }

function openLocalItemMenu(entry) {
  selectedEntry = entry;
  selectedEntrySource = 'local';
  document.getElementById('sftp-item-title').textContent = entry.name;
  document.getElementById('sftp-item-path').textContent = decodeURIComponent(entry.uri);
  document.getElementById('sftp-item-dialog').showModal();
}

function uploadLocalFile() {
  closeLocalActions();
  const current = localStack[localStack.length - 1];
  if (!current) return;
  callBridge(null, (id) => bridge.localUpload(id, current.uri))
    .then(() => { sftpToast('File diunggah'); loadLocalList(); })
    .catch((err) => sftpToast(err.message));
}

function importLocalZip() {
  closeLocalActions();
  const current = localStack[localStack.length - 1];
  if (!current) return;
  callBridge(null, (id) => bridge.localImportZip(id, current.uri))
    .then(() => { sftpToast('ZIP diimpor'); loadLocalList(); })
    .catch((err) => sftpToast(err.message));
}

function toggleSftpAuth() {
  const mode = document.getElementById('sftp-auth').value;
  document.getElementById('key-fields').classList.toggle('visible', mode === 'key');
  document.getElementById('password-field').style.display = mode === 'key' ? 'none' : '';
}

function buildConnectConfig() {
  const mode = document.getElementById('sftp-auth').value;
  const config = {
    host: document.getElementById('sftp-host').value.trim(),
    port: parseInt(document.getElementById('sftp-port').value, 10) || 22,
    username: document.getElementById('sftp-user').value.trim(),
  };
  if (mode === 'key') {
    config.privateKeyPath = document.getElementById('sftp-key-path').value;
    config.passphrase = document.getElementById('sftp-passphrase').value;
    if (!config.privateKeyPath) throw new Error('Pilih private key terlebih dahulu');
  } else {
    config.password = document.getElementById('sftp-password').value;
    if (!config.password) throw new Error('Password wajib diisi');
  }
  return config;
}

async function connectSftp(event) {
  event.preventDefault();
  const button = document.getElementById('sftp-connect-button');
  const originalLabel = button.textContent;
  try {
    const config = buildConnectConfig();
    // Mode edit hanya memperbarui data tersimpan, tidak ikut menyambung.
    if (editingConnectionId) {
      const label = document.getElementById('sftp-label').value.trim();
      callSync(
        () => bridge.updateConnection(editingConnectionId, JSON.stringify(config), label),
        'Simpan koneksi'
      );
      editingConnectionId = null;
      sftpToast('Koneksi diperbarui');
      showExplorerView('home');
      renderExplorerHome();
      return;
    }
    pendingSavedConnect = null;
    pendingConnectConfig = config;
    button.disabled = true; button.textContent = 'Menghubungkan…';
    const data = await callBridge('conn-' + Date.now(), (id) => bridge.sftpConnect(id, JSON.stringify(config)));
    handleConnectResult(data, config);
  } catch (err) {
    sftpToast(err.message);
  } finally {
    button.disabled = false; button.textContent = originalLabel;
  }
}

function handleConnectResult(data, config) {
  if (data.status === 'hostKey') {
    document.getElementById('sftp-fingerprint').textContent = data.fingerprint;
    pendingConnectConfig = Object.assign({}, config, { fingerprint: data.fingerprint });
    document.getElementById('sftp-hostkey-dialog').showModal();
    return;
  }
  onConnected(config, data.home || '/', { persist: true });
}

async function trustHostKey() {
  document.getElementById('sftp-hostkey-dialog').close();
  // Koneksi tersimpan memakai jalur khusus agar kredensial tetap di sisi native.
  if (pendingSavedConnect && pendingSavedConnect.fingerprint) {
    const target = pendingSavedConnect;
    pendingSavedConnect = null;
    try {
      const data = await callBridge('conn-' + Date.now(),
        (id) => bridge.sftpTrustSavedHostKey(id, target.id, target.fingerprint));
      if (data.status === 'connected') {
        onConnected(null, data.home || '/', { label: data.label || target.label });
      }
    } catch (err) { sftpToast(err.message); }
    return;
  }
  if (!pendingConnectConfig) return;
  try {
    const config = pendingConnectConfig;
    const data = await callBridge('conn-' + Date.now(), (id) => bridge.sftpTrustHostKey(id, JSON.stringify(config)));
    if (data.status === 'connected') onConnected(config, data.home || '/', { persist: true });
  } catch (err) { sftpToast(err.message); }
}

function rejectHostKey() {
  document.getElementById('sftp-hostkey-dialog').close();
  pendingConnectConfig = null;
  pendingSavedConnect = null;
  sftpToast('Koneksi dibatalkan');
}

/**
 * config null = tersambung lewat koneksi tersimpan (label datang dari native).
 * options.persist = simpan koneksi baru bila checkbox "Simpan koneksi" aktif.
 */
function onConnected(config, home, options) {
  const opts = options || {};
  sftpConnected = true;
  sftpServerLabel.textContent = opts.label || (config ? `${config.username}@${config.host}` : 'SFTP aktif');
  if (opts.persist && config) persistConnection(config);
  sftpHomeDir = home || '/';
  showExplorerView('sftp');
  navigateTo(home || '/');
}

async function disconnectSftp() {
  try { await callBridge(null, (id) => bridge.sftpDisconnect(id)); } catch (_) {}
  sftpConnected = false;
  sftpListEl.innerHTML = '';
  closeSftpActions();
  // Kembali ke hub, bukan ke form koneksi — daftar koneksi tersimpan ada di hub.
  showExplorerView('home');
  renderExplorerHome();
}

function navigateTo(path) {
  currentDir = path || '/';
  renderBreadcrumb();
  sftpPathLabel.textContent = currentDir;
  if (settings.autoList) listDir();
  else renderRefreshPrompt();
}

function renderBreadcrumb() {
  sftpBreadcrumb.innerHTML = '';
  const parts = currentDir.split('/').filter(Boolean);
  const root = document.createElement('button');
  root.className = 'crumb'; root.textContent = '/';
  root.onclick = () => navigateTo('/');
  sftpBreadcrumb.appendChild(root);
  let acc = '';
  parts.forEach((part) => {
    acc += '/' + part;
    const target = acc;
    const crumb = document.createElement('button');
    crumb.className = 'crumb'; crumb.textContent = part;
    crumb.onclick = () => navigateTo(target);
    sftpBreadcrumb.appendChild(crumb);
  });
}

function renderState(message) {
  sftpListEl.innerHTML = '';
  const box = document.createElement('div');
  box.className = 'state-box'; box.textContent = message;
  sftpListEl.appendChild(box);
}

function renderRefreshPrompt() {
  sftpListEl.innerHTML = '';
  const box = document.createElement('div');
  box.className = 'state-box';
  const label = document.createElement('div'); label.textContent = 'Refresh otomatis nonaktif.';
  const btn = document.createElement('button'); btn.className = 'secondary-action'; btn.textContent = 'Muat direktori'; btn.onclick = listDir;
  box.append(label, btn);
  sftpListEl.appendChild(box);
}

async function listDir() {
  renderState('Memuat…');
  try {
    const entries = await callBridge(null, (id) => bridge.sftpList(id, currentDir, settings.showHidden, settings.sortAscending));
    renderEntries(entries || []);
  } catch (err) { renderState('Gagal memuat: ' + err.message); }
}
function refreshSftp() { if (sftpConnected) listDir(); }

function formatBytes(bytes) {
  if (!bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  return (bytes / Math.pow(1024, i)).toFixed(i ? 1 : 0) + ' ' + units[i];
}

function renderEntries(entries) {
  sftpListEl.innerHTML = '';
  if (!entries.length) { renderState('Folder kosong'); return; }
  entries.forEach((entry) => {
    const row = document.createElement('button');
    row.type = 'button'; row.className = 'file-row'; row.setAttribute('role', 'listitem');
    const icon = document.createElement('div'); icon.className = 'file-icon';
    const isImg = !entry.directory && isImageName(entry.name);
    icon.textContent = entry.directory ? 'DIR' : (isImg ? 'IMG' : (entry.name.split('.').pop().slice(0, 3).toUpperCase() || 'TXT'));
    const info = document.createElement('div'); info.className = 'file-info';
    const name = document.createElement('span'); name.className = 'file-name'; name.textContent = entry.name; // textContent mencegah HTML injection
    const meta = document.createElement('span'); meta.className = 'file-meta';
    meta.textContent = entry.directory
      ? 'Folder'
      : `${formatBytes(entry.size)} · ${new Date(entry.modified).toLocaleDateString('id-ID')}${isImg ? ' · gambar' : ''}`;
    info.append(name, meta);
    row.append(icon, info);
    attachRowGestures(row, entry);
    sftpListEl.appendChild(row);
  });
}

// Tap = buka, long-press (500ms) = menu aksi. Dibatalkan saat scroll/gerak.
function attachRowGestures(row, entry) {
  let timer = null; let longPressed = false; let startY = 0;
  const cancel = () => { clearTimeout(timer); timer = null; };
  row.addEventListener('pointerdown', (e) => {
    longPressed = false; startY = e.clientY;
    timer = setTimeout(() => { longPressed = true; openItemMenu(entry); }, 500);
  });
  row.addEventListener('pointermove', (e) => { if (Math.abs(e.clientY - startY) > 10) cancel(); });
  row.addEventListener('pointerup', cancel);
  row.addEventListener('pointercancel', cancel);
  row.addEventListener('click', () => {
    if (longPressed) { longPressed = false; return; }
    if (entry.directory) navigateTo(entry.path);
    else openRemoteFile(entry);
  });
}

/**
 * Gambar tidak punya mode edit teks: dibaca sebagai biner lalu dibuka di viewer
 * (Fitur D.1). File lain tetap masuk editor. Cek tipe dilakukan di satu tempat
 * ini saja — native yang menentukan state "sumber file" (activeRemotePath).
 */
async function openRemoteFile(entry) {
  const image = isImageName(entry.name);
  if (image && !bridge.sftpOpenImage) { sftpToast('Fitur ini butuh versi aplikasi terbaru'); return; }
  sftpToast('Membuka ' + entry.name + '…');
  try {
    await callBridge(null, (id) =>
      image ? bridge.sftpOpenImage(id, entry.path) : bridge.sftpOpenFile(id, entry.path)
    );
    closeSftpPanel();
  } catch (err) { sftpToast(err.message); }
}

/* ── Menu tambah ── */
function toggleSftpActions() { sftpActionMenu.classList.toggle('open'); }
function closeSftpActions() { sftpActionMenu.classList.remove('open'); }

function promptCreate(kind) {
  closeSftpActions();
  closeLocalActions();
  pendingCreateKind = kind;
  selectedEntry = null;
  selectedEntrySource = explorerView === 'local' ? 'local' : 'sftp';
  document.getElementById('sftp-input-title').textContent = kind === 'folder' ? 'Folder baru' : 'File baru';
  const input = document.getElementById('sftp-input-value');
  input.value = '';
  document.getElementById('sftp-input-dialog').showModal();
  setTimeout(() => input.focus(), 50);
}

async function submitSftpInput(event) {
  event.preventDefault();
  const value = document.getElementById('sftp-input-value').value.trim();
  if (!value) return;
  if (value.includes('/') || value === '.' || value === '..') { sftpToast('Nama tidak valid'); return; }
  document.getElementById('sftp-input-dialog').close();
  try {
    if (selectedEntrySource === 'local') {
      const current = localStack[localStack.length - 1];
      if (!current) throw new Error('Folder lokal tidak tersedia');
      if (selectedEntry) {
        const renamed = await callBridge(null, (id) => bridge.localRename(id, selectedEntry.uri, value));
        const stackItem = localStack.find((item) => item.uri === selectedEntry.uri);
        if (stackItem) { stackItem.uri = renamed.uri; stackItem.label = value; }
        sftpToast('Nama diubah');
      } else {
        await callBridge(null, (id) => bridge.localCreate(id, current.uri, value, pendingCreateKind === 'folder'));
        sftpToast(pendingCreateKind === 'folder' ? 'Folder dibuat' : 'File dibuat');
      }
      loadLocalList();
    } else {
      if (selectedEntry) {
        await callBridge(null, (id) => bridge.sftpRename(id, currentDir, selectedEntry.name, value));
        sftpToast('Nama diubah');
      } else if (pendingCreateKind === 'folder') {
        await callBridge(null, (id) => bridge.sftpCreateFolder(id, currentDir, value));
        sftpToast('Folder dibuat');
      } else {
        await callBridge(null, (id) => bridge.sftpCreateFile(id, currentDir, value));
        sftpToast('File dibuat');
      }
      listDir();
    }
  } catch (err) { sftpToast(err.message); }
  selectedEntry = null;
}

function uploadSftpFile() {
  closeSftpActions();
  callBridge(null, (id) => bridge.sftpUpload(id, currentDir))
    .then(() => { sftpToast('File diunggah'); listDir(); })
    .catch((err) => sftpToast(err.message));
}

function importSftpZip() {
  closeSftpActions();
  callBridge(null, (id) => bridge.sftpImportZip(id, currentDir))
    .then(() => { sftpToast('ZIP diimpor'); listDir(); })
    .catch((err) => sftpToast(err.message));
}

/* ── Aksi per item ── */
function openItemMenu(entry) {
  selectedEntry = entry;
  selectedEntrySource = 'sftp';
  document.getElementById('sftp-item-title').textContent = entry.name;
  document.getElementById('sftp-item-path').textContent = entry.path;
  document.getElementById('sftp-item-dialog').showModal();
}

function renameSelectedItem() {
  document.getElementById('sftp-item-dialog').close();
  if (!selectedEntry) return;
  pendingCreateKind = null;
  document.getElementById('sftp-input-title').textContent = 'Ubah nama';
  const input = document.getElementById('sftp-input-value');
  input.value = selectedEntry.name;
  document.getElementById('sftp-input-dialog').showModal();
  setTimeout(() => input.focus(), 50);
}

async function deleteSelectedItem() {
  document.getElementById('sftp-item-dialog').close();
  if (!selectedEntry) return;
  const entry = selectedEntry;
  const local = selectedEntrySource === 'local';
  const warning = local
    ? `Hapus permanen "${entry.name}"${entry.directory ? ' beserta seluruh isinya' : ''} dari perangkat? Tindakan ini tidak dapat dibatalkan.`
    : `Hapus "${entry.name}"${entry.directory ? ' beserta seluruh isinya' : ''}?`;
  const ok = await showConfirm(warning, 'Hapus', 'Batal');
  if (ok !== 'ok') return;
  try {
    if (local) {
      await callBridge(null, (id) => bridge.localDelete(id, entry.uri));
      loadLocalList();
    } else {
      await callBridge(null, (id) => bridge.sftpDelete(id, entry.path));
      if (remoteFileHint === entry.path) remoteFileHint = null;
      listDir();
    }
    sftpToast('Terhapus');
  } catch (err) { sftpToast(err.message); }
  selectedEntry = null;
}

function copySelectedUri() {
  document.getElementById('sftp-item-dialog').close();
  if (!selectedEntry) return;
  const label = sftpServerLabel.textContent;
  const uri = selectedEntrySource === 'local'
    ? selectedEntry.uri
    : `sftp://${label}${selectedEntry.path}`;
  if (navigator.clipboard) navigator.clipboard.writeText(uri).then(() => sftpToast('URI disalin')).catch(() => sftpToast(uri));
  else sftpToast(uri);
  selectedEntry = null;
}

function pickSftpKey() {
  callBridge(null, (id) => bridge.sftpPickPrivateKey(id))
    .then((data) => {
      document.getElementById('sftp-key-path').value = data.path;
      document.getElementById('sftp-key-button').textContent = data.name || 'Key dipilih';
    })
    .catch((err) => sftpToast(err.message));
}

/* ── Pengaturan ── */
function loadSftpSettings() {
  if (bridge && bridge.loadSettings) {
    try {
      const saved = JSON.parse(bridge.loadSettings());
      Object.assign(settings, saved);
    } catch (_) {}
  }
}
function openSftpSettings() {
  document.getElementById('setting-sort').checked = settings.sortAscending;
  document.getElementById('setting-hidden').checked = settings.showHidden;
  document.getElementById('setting-autolist').checked = settings.autoList;
  document.getElementById('sftp-settings-dialog').showModal();
}
function saveSftpSettings() {
  settings.sortAscending = document.getElementById('setting-sort').checked;
  settings.showHidden = document.getElementById('setting-hidden').checked;
  settings.autoList = document.getElementById('setting-autolist').checked;
  if (bridge && bridge.saveSettings) bridge.saveSettings(JSON.stringify(settings));
  document.getElementById('sftp-settings-dialog').close();
  if (explorerView === 'sftp' && sftpConnected) navigateTo(currentDir);
  // Listing lokal juga memakai setting yang sama (dibaca native dari SharedPreferences).
  else if (explorerView === 'local' && localStack.length) loadLocalList();
}
