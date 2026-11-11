'use strict';
// Prism Remote: Prism on the phone, played from this browser. Talks only to the phone it was loaded from.
(() => {
  const $ = (id) => document.getElementById(id);
  const svg = (d, fill = true) => `<svg viewBox="0 0 24 24" aria-hidden="true">${fill ? `<path fill="currentColor" d="${d}"/>` : `<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="${d}"/>`}</svg>`;
  const ICON = {
    play: svg('M8 5.6v12.8a1 1 0 0 0 1.53.85l10.2-6.4a1 1 0 0 0 0-1.7L9.53 4.75A1 1 0 0 0 8 5.6z'),
    pause: svg('M7 5h3.2v14H7zM13.8 5H17v14h-3.2z'),
    next: svg('M5.5 6.2v11.6a.9.9 0 0 0 1.38.76l8.62-5.8a.9.9 0 0 0 0-1.5L6.88 5.43a.9.9 0 0 0-1.38.77zM16.6 5.5h2.4v13h-2.4z'),
    prev: svg('M18.5 6.2v11.6a.9.9 0 0 1-1.38.76L8.5 12.76a.9.9 0 0 1 0-1.5l8.62-5.83a.9.9 0 0 1 1.38.77zM5 5.5h2.4v13H5z'),
    shuffle: svg('M3 7h3.5c2 0 3.2 1 4.3 2.7l2.4 4.6c1 1.7 2.3 2.7 4.3 2.7H21M18 14l3 3-3 3M3 17h3.5c1.3 0 2.3-.5 3.1-1.3M13.4 8.3C14.2 7.5 15.2 7 16.5 7H21M18 4l3 3-3 3', false),
    repeat: svg('M4 11V9.5A3.5 3.5 0 0 1 7.5 6H19M16 3l3 3-3 3M20 13v1.5a3.5 3.5 0 0 1-3.5 3.5H5M8 21l-3-3 3-3', false),
    x: svg('M6 6l12 12M18 6L6 18', false),
    up: svg('M12 19V5M6 11l6-6 6 6', false),
    down: svg('M12 5v14M6 13l6 6 6-6', false),
    plus: svg('M12 5v14M5 12h14', false),
    playNext: svg('M4 6h11M4 11h11M4 16h6M17 13v8M13 17h8', false),
    playSmall: svg('M8 5.6v12.8a1 1 0 0 0 1.53.85l10.2-6.4a1 1 0 0 0 0-1.7L9.53 4.75A1 1 0 0 0 8 5.6z'),
    volDown: svg('M4 9.5v5h3.5L12 19V5L7.5 9.5H4z'),
    volUp: svg('M4 9.5v5h3.5L12 19V5L7.5 9.5H4zM15.5 8.5a5 5 0 0 1 0 7M18 6a8.5 8.5 0 0 1 0 12'),
  };

  let state = null;
  let stateAt = 0;
  let events = null;
  let offlineTimer = 0;
  let seeking = false;
  let volumeDragAt = 0;
  let lyricsFor = null;
  let lyrics = null;
  let lineEls = [];
  let lastLine = -2;
  let userScrolledAt = 0;
  let queueSig = '';
  let results = [];

  // ------------------------------------------------------------ Talking to the phone

  async function api(path, body) {
    const opts = body === undefined
      ? { credentials: 'same-origin', cache: 'no-store' }
      : { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-Prism': '1' }, body: JSON.stringify(body) };
    const r = await fetch(path, opts);
    if (r.status === 401) { showPair(); throw new Error('not paired'); }
    return r;
  }

  async function send(path, body, failText) {
    try {
      const r = await api(path, body);
      if (!r.ok) toast(failText || "Prism couldn't do that");
      return r.ok;
    } catch (e) {
      if (e.message !== 'not paired') toast("Can't reach Prism");
      return false;
    }
  }

  async function hello() {
    try {
      const r = await fetch('/api/hello', { credentials: 'same-origin', cache: 'no-store' });
      const h = await r.json();
      $('pair-phone').textContent = `Pair this browser with ${h.name}.`;
      if (h.paired) showApp(); else showPair();
    } catch (e) {
      showPair();
      $('pair-error').textContent = "Can't reach Prism. Check that it's open on your phone and on the same Wi-Fi.";
    }
  }

  function showPair() {
    if (events) { events.close(); events = null; }
    $('app').hidden = true;
    $('pair').hidden = false;
    setBackdrop(null);
    document.title = 'Prism Remote';
    setTimeout(() => $('code').focus(), 50);
  }

  function showApp() {
    $('pair').hidden = true;
    $('app').hidden = false;
    connect();
  }

  function connect() {
    if (events) events.close();
    events = new EventSource('/api/events');
    events.addEventListener('state', (e) => {
      clearTimeout(offlineTimer);
      $('offline').hidden = true;
      try { render(JSON.parse(e.data)); } catch (err) { /* a broken message: the next one will do */ }
    });
    events.onerror = () => {
      clearTimeout(offlineTimer);
      offlineTimer = setTimeout(async () => {
        $('offline').hidden = false;
        // Unpaired on the phone meanwhile: back to pairing.
        try {
          const h = await (await fetch('/api/hello', { credentials: 'same-origin', cache: 'no-store' })).json();
          if (!h.paired) showPair();
        } catch (e) { /* still away */ }
      }, 2500);
    };
  }

  function browserName() {
    const ua = navigator.userAgent;
    const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
      : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'A browser';
    const os = /Windows/.test(ua) ? 'Windows' : /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad'
      : /Android/.test(ua) ? 'Android' : /CrOS/.test(ua) ? 'ChromeOS' : /Mac OS X/.test(ua) ? 'Mac' : /Linux/.test(ua) ? 'Linux' : '';
    return os ? `${browser} on ${os}` : browser;
  }

  // ------------------------------------------------------------ Pairing

  $('code').addEventListener('input', (e) => {
    const d = e.target.value.replace(/\D/g, '').slice(0, 6);
    e.target.value = d.length > 3 ? `${d.slice(0, 3)} ${d.slice(3)}` : d;
    $('pair-error').textContent = '';
  });

  $('pair-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const code = $('code').value.replace(/\D/g, '');
    const err = $('pair-error');
    if (code.length !== 6) { err.textContent = 'The code has six digits.'; return; }
    const button = e.target.querySelector('button');
    button.disabled = true;
    err.textContent = '';
    try {
      const r = await fetch('/api/pair', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-Prism': '1' },
        body: JSON.stringify({ code, name: browserName() }),
      });
      if (r.ok) { $('code').value = ''; showApp(); return; }
      const j = await r.json().catch(() => ({}));
      if (r.status === 429) err.textContent = `Too many tries. Wait ${j.seconds || 60} seconds, then use the code on the phone.`;
      else err.textContent = "That code doesn't match. Check the one on your phone (a new one shows after each pairing).";
    } catch (ex) {
      err.textContent = "Can't reach Prism on your phone.";
    } finally {
      button.disabled = false;
    }
  });

  // ------------------------------------------------------------ Drawing what's playing

  const fmt = (ms) => {
    if (!isFinite(ms) || ms < 0) ms = 0;
    const s = Math.floor(ms / 1000);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const ss = String(s % 60).padStart(2, '0');
    return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
  };

  function setBackdrop(url) {
    const b = $('backdrop');
    const v = url ? `url("${url.replace(/"/g, '%22')}")` : 'none';
    if (b.style.backgroundImage !== v) b.style.backgroundImage = v;
  }

  function position() {
    if (!state) return 0;
    let p = state.positionMs;
    if (state.playing && !state.buffering) p += performance.now() - stateAt;
    return state.durationMs > 0 ? Math.min(p, state.durationMs) : p;
  }

  function render(s) {
    const songChanged = !state || (state.song && state.song.s.id) !== (s.song && s.song.s.id);
    state = s;
    stateAt = performance.now();
    const song = s.song;
    $('title').textContent = song ? song.title : 'Nothing playing';
    $('artist').textContent = song ? song.artist : 'Play something on Prism, or search here';
    $('source').textContent = s.source || '';
    $('output').textContent = s.output === 'This phone' ? '' : `Playing on ${s.output}`;
    const art = $('art');
    if (song && song.art) { if (art.getAttribute('src') !== song.art) art.src = song.art; } else art.removeAttribute('src');
    setBackdrop(song && song.art);
    document.title = song ? `${song.title} · ${song.artist}` : 'Prism Remote';

    const play = $('play');
    play.innerHTML = s.playing ? ICON.pause : ICON.play;
    play.setAttribute('aria-label', s.playing ? 'Pause' : 'Play');
    $('next').disabled = !s.canNext;
    const shuffle = $('shuffle');
    shuffle.classList.toggle('on', s.shuffle);
    shuffle.innerHTML = ICON.shuffle + (s.smart ? '<span class="badge">✦</span>' : '');
    shuffle.setAttribute('aria-label', s.smart ? 'Smart Shuffle' : s.shuffle ? 'Shuffle on' : 'Shuffle off');
    const repeat = $('repeat');
    repeat.classList.toggle('on', s.repeat !== 0);
    repeat.innerHTML = ICON.repeat + (s.repeat === 1 ? '<span class="badge">1</span>' : '');
    repeat.setAttribute('aria-label', s.repeat === 1 ? 'Repeat one' : s.repeat === 2 ? 'Repeat all' : 'Repeat off');

    const vol = $('volume');
    vol.disabled = s.volume < 0;
    if (performance.now() - volumeDragAt > 1500 && s.volume >= 0) { vol.value = s.volume; fill(vol); }
    $('dur').textContent = fmt(s.durationMs);
    renderQueue(s);
    if (songChanged) loadLyrics(song ? song.s.id : null);
  }

  function fill(range) {
    const min = Number(range.min) || 0;
    const max = Number(range.max) || 100;
    range.style.setProperty('--fill', `${((Number(range.value) - min) / (max - min || 1)) * 100}%`);
  }

  function frame() {
    if (state) {
      const p = position();
      if (!seeking) {
        $('pos').textContent = fmt(p);
        const seek = $('seek');
        seek.value = state.durationMs > 0 ? Math.round((p / state.durationMs) * 1000) : 0;
        fill(seek);
      }
      highlight(p + (state.lyricShift || 0));
    }
    requestAnimationFrame(frame);
  }

  // ------------------------------------------------------------ Lyrics

  async function loadLyrics(id) {
    lyricsFor = id;
    lyrics = null;
    lineEls = [];
    lastLine = -2;
    const box = $('lyrics');
    box.className = 'lyrics';
    if (!id) { box.innerHTML = '<div class="empty">Lyrics show here for what’s playing.</div>'; return; }
    box.innerHTML = '<div class="empty">Looking for lyrics…</div>';
    try {
      const r = await api('/api/lyrics');
      const l = await r.json();
      if (lyricsFor !== id) return;
      // The phone moved on while they were looked up: these are for the song before.
      if (l.id !== id) { setTimeout(() => { if (lyricsFor === id) loadLyrics(id); }, 1500); return; }
      lyrics = l;
      buildLyrics(l);
    } catch (e) {
      if (lyricsFor === id) box.innerHTML = '<div class="empty">Couldn’t load the lyrics.</div>';
    }
  }

  function wordSpan(text, w) {
    const s = document.createElement('span');
    s.className = 'w';
    s.textContent = text;
    s.dataset.t = w.t;
    s.dataset.e = w.e;
    return s;
  }

  function buildLyrics(l) {
    const box = $('lyrics');
    box.innerHTML = '';
    if (!l.lines.length) { box.innerHTML = '<div class="empty">No lyrics for this song.</div>'; return; }
    if (!l.synced) box.classList.add('plain');
    for (const line of l.lines) {
      const el = document.createElement('div');
      el.className = 'line' + (line.gap ? ' gap' : '') + (line.end ? ' end' : '');
      if (line.gap) el.textContent = '• • •';
      else if (line.w && line.w.length) {
        // The words laid onto the line's own text, so its spaces and punctuation stay as they are.
        let at = 0;
        const text = line.x;
        for (const w of line.w) {
          const word = w.w.trim();
          const i = word ? text.indexOf(word, at) : -1;
          if (i >= 0) {
            if (i > at) el.appendChild(document.createTextNode(text.slice(at, i)));
            el.appendChild(wordSpan(text.slice(i, i + word.length), w));
            at = i + word.length;
          } else {
            el.appendChild(wordSpan(w.w, w));
            if (!/\s$/.test(w.w)) el.appendChild(document.createTextNode(' '));
          }
        }
        if (at < text.length) el.appendChild(document.createTextNode(text.slice(at)));
      } else el.textContent = line.x || ' ';
      if (line.bg && line.bg.length) {
        const bg = document.createElement('span');
        bg.className = 'bg';
        line.bg.forEach((w, i) => {
          bg.appendChild(wordSpan(w.w, w));
          if (i < line.bg.length - 1 && !/\s$/.test(w.w)) bg.appendChild(document.createTextNode(' '));
        });
        el.appendChild(bg);
      }
      // How far through the line the song is: a thin line of split light under it, grown while it's sung.
      if (l.synced && !line.gap) {
        const prog = document.createElement('span');
        prog.className = 'prog';
        el.appendChild(prog);
      }
      if (l.synced) el.addEventListener('click', () => seekTo(line.t - (state ? state.lyricShift || 0 : 0)));
      box.appendChild(el);
      lineEls.push(el);
    }
    if (l.source) {
      const note = document.createElement('div');
      note.className = 'source-note';
      note.textContent = `Lyrics from ${l.source}`;
      box.appendChild(note);
    }
  }

  function highlight(t) {
    if (!lyrics || !lyrics.synced || !lineEls.length) return;
    const lines = lyrics.lines;
    let now = -1;
    for (let i = 0; i < lines.length; i++) { if (lines[i].t <= t) now = i; else break; }
    if (now !== lastLine) {
      lineEls.forEach((el, i) => {
        el.classList.toggle('past', i < now);
        el.classList.toggle('now', i === now);
        if (i !== now) {
          el.querySelectorAll('.w').forEach((w) => { w.style.fontWeight = ''; w.style.color = ''; w.style.letterSpacing = ''; });
          const prog = el.querySelector('.prog');
          if (prog) prog.style.width = '';
        }
      });
      lastLine = now;
      if (now >= 0 && performance.now() - userScrolledAt > 3000) {
        const panel = $('tab-lyrics');
        const el = lineEls[now];
        panel.scrollTo({ top: el.offsetTop - panel.clientHeight * 0.38, behavior: 'smooth' });
      }
    }
    if (now >= 0) {
      const el = lineEls[now];
      // Each word thickens from Light to Medium and brightens as it's sung, giving its extra width back as letter
      // spacing so the line doesn't shift (as on the phone).
      el.querySelectorAll('.w').forEach((w) => {
        const a = Number(w.dataset.t);
        const b = Number(w.dataset.e);
        const p = t <= a ? 0 : t >= b || b <= a ? 1 : (t - a) / (b - a);
        const step = Math.round(p * 10);
        w.style.fontWeight = String(300 + step * 20);
        w.style.color = `rgba(255, 255, 255, ${0.42 + 0.058 * step})`;
        const k = spreadOf(w);
        w.style.letterSpacing = k ? `${(-k * step / 10).toFixed(3)}px` : '';
      });
      const prog = el.querySelector('.prog');
      if (prog) {
        const start = lines[now].t;
        const next = lines.slice(now + 1).find((x) => x.t > start);
        const end = next ? next.t : start + 4000;
        prog.style.width = `${Math.max(0, Math.min(1, (t - start) / Math.max(1, end - start))) * 70}%`;
      }
    }
  }

  // How much wider each letter of a word is at Medium than at Light, in px (measured once per word, in Outfit).
  const measure = document.createElement('canvas').getContext('2d');
  function spreadOf(w) {
    if (w.dataset.k !== undefined) return Number(w.dataset.k);
    const text = w.textContent || '';
    if (!text.trim() || !measure) { w.dataset.k = '0'; return 0; }
    const cs = getComputedStyle(w);
    const size = cs.fontSize;
    const family = cs.fontFamily;
    measure.font = `300 ${size} ${family}`;
    const light = measure.measureText(text).width;
    measure.font = `500 ${size} ${family}`;
    const medium = measure.measureText(text).width;
    const k = Math.max(0, (medium - light) / text.length);
    w.dataset.k = String(k);
    return k;
  }
  // Measured once Outfit has loaded, so the first words aren't measured in a fallback font.
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => document.querySelectorAll('.w').forEach((w) => { delete w.dataset.k; }));

  ['wheel', 'touchmove', 'keydown'].forEach((ev) => $('tab-lyrics').addEventListener(ev, () => { userScrolledAt = performance.now(); }, { passive: true }));

  // ------------------------------------------------------------ Queue

  function row(item, actions) {
    const li = document.createElement('li');
    li.className = 'row' + (item.at ? ` ${item.at}` : '');
    const img = document.createElement('img');
    img.className = 'thumb';
    img.alt = '';
    img.loading = 'lazy';
    if (item.song.art) img.src = item.song.art;
    const text = document.createElement('div');
    text.className = 'text';
    const t = document.createElement('div');
    t.className = 't';
    t.textContent = item.song.title;
    const a = document.createElement('div');
    a.className = 'a';
    a.textContent = item.song.artist;
    text.append(t, a);
    const acts = document.createElement('div');
    acts.className = 'actions';
    for (const [icon, label, fn] of actions) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'mini';
      b.innerHTML = ICON[icon];
      b.title = label;
      b.setAttribute('aria-label', label);
      b.addEventListener('click', (e) => { e.stopPropagation(); fn(); });
      acts.appendChild(b);
    }
    li.append(img, text, acts);
    return li;
  }

  function label(text) {
    const li = document.createElement('li');
    li.className = 'section-label';
    li.textContent = text;
    return li;
  }

  function renderQueue(s) {
    const sig = s.queue.map((q) => `${q.index}:${q.song.s.id}:${q.at}`).join('|') + `|${s.shuffle}`;
    if (sig === queueSig) return;
    queueSig = sig;
    const ol = $('queue');
    ol.innerHTML = '';
    if (!s.queue.length) { ol.innerHTML = '<li class="empty">The queue is empty.</li>'; return; }
    const played = s.queue.filter((q) => q.at === 'played');
    const now = s.queue.filter((q) => q.at === 'now');
    const next = s.queue.filter((q) => q.at === 'next');
    if (played.length) { ol.appendChild(label('Played')); played.forEach((q) => ol.appendChild(clickable(row(q, [['x', 'Remove', () => queue('remove', q.index)]]), q))); }
    if (now.length) { ol.appendChild(label('Now playing')); now.forEach((q) => ol.appendChild(row(q, []))); }
    if (next.length) {
      ol.appendChild(label(`Up next · ${next.length}`));
      next.forEach((q, i) => {
        const acts = [];
        // Moving is by the queue's own order, which is the play order unless it's shuffled.
        if (!s.shuffle) {
          if (i > 0) acts.push(['up', 'Move up', () => queue('move', q.index, q.index - 1)]);
          if (i < next.length - 1) acts.push(['down', 'Move down', () => queue('move', q.index, q.index + 1)]);
        }
        acts.push(['x', 'Remove', () => queue('remove', q.index)]);
        ol.appendChild(clickable(row(q, acts), q));
      });
    }
    const nowRow = ol.querySelector('.row.now');
    if (nowRow && !$('tab-queue').hidden && performance.now() - userScrolledAt > 3000) nowRow.scrollIntoView({ block: 'nearest' });
  }

  function clickable(li, q) {
    li.addEventListener('click', () => queue('jump', q.index));
    return li;
  }

  function queue(action, index, to) {
    send('/api/queue', { action, index, to: to === undefined ? -1 : to });
  }

  // ------------------------------------------------------------ Search

  $('search-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('q').value.trim();
    const ul = $('results');
    if (!q) { ul.innerHTML = ''; return; }
    ul.innerHTML = '<li class="empty">Searching…</li>';
    try {
      const r = await api(`/api/search?q=${encodeURIComponent(q)}`);
      const j = await r.json();
      results = j.songs || [];
      ul.innerHTML = '';
      if (!results.length) { ul.innerHTML = `<li class="empty">${j.error ? 'Search didn’t answer. Try again.' : 'No songs found.'}</li>`; return; }
      results.forEach((song) => {
        const item = { song, at: '' };
        const li = row(item, [
          ['playSmall', 'Play now', () => playSong(song, 'now')],
          ['playNext', 'Play next', () => playSong(song, 'next')],
          ['plus', 'Add to queue', () => playSong(song, 'later')],
        ]);
        li.addEventListener('click', () => playSong(song, 'now'));
        ul.appendChild(li);
      });
    } catch (ex) {
      if (ex.message !== 'not paired') ul.innerHTML = '<li class="empty">Search didn’t answer. Try again.</li>';
    }
  });

  async function playSong(song, mode) {
    const ok = await send('/api/play', { song, mode }, "Prism couldn't play that");
    if (ok) toast(mode === 'now' ? `Playing ${song.title}` : mode === 'next' ? `${song.title} plays next` : `${song.title} added to the queue`);
  }

  // ------------------------------------------------------------ Controls

  function control(action, value) { return send('/api/control', value === undefined ? { action } : { action, value }); }

  function seekTo(ms) {
    if (!state) return;
    const t = Math.max(0, Math.round(ms));
    state.positionMs = t;
    stateAt = performance.now();
    lastLine = -2;
    control('seek', t);
  }

  $('play').addEventListener('click', () => {
    if (state) { state.positionMs = position(); stateAt = performance.now(); state.playing = !state.playing; $('play').innerHTML = state.playing ? ICON.pause : ICON.play; }
    control('toggle');
  });
  $('next').addEventListener('click', () => control('next'));
  $('prev').addEventListener('click', () => control('previous'));
  $('shuffle').addEventListener('click', () => control('shuffle'));
  $('repeat').addEventListener('click', () => control('repeat'));
  $('prev').innerHTML = ICON.prev;
  $('next').innerHTML = ICON.next;
  $('play').innerHTML = ICON.play;
  $('shuffle').innerHTML = ICON.shuffle;
  $('repeat').innerHTML = ICON.repeat;
  $('vol-down').innerHTML = ICON.volDown;
  $('vol-up').innerHTML = ICON.volUp;

  const seek = $('seek');
  seek.addEventListener('input', () => {
    seeking = true;
    fill(seek);
    if (state) $('pos').textContent = fmt((seek.value / 1000) * state.durationMs);
  });
  seek.addEventListener('change', () => {
    if (state && state.durationMs > 0) seekTo((seek.value / 1000) * state.durationMs);
    seeking = false;
  });

  let volTimer = 0;
  const vol = $('volume');
  vol.addEventListener('input', () => {
    volumeDragAt = performance.now();
    fill(vol);
    clearTimeout(volTimer);
    volTimer = setTimeout(() => control('volume', Number(vol.value)), 90);
  });

  $('unpair').addEventListener('click', async () => {
    if (!confirm('Unpair this browser? You’ll need a new code from the phone to use it again.')) return;
    await send('/api/forget', {});
    showPair();
  });

  document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => {
    document.querySelectorAll('.tabs button').forEach((o) => o.setAttribute('aria-selected', String(o === b)));
    ['lyrics', 'queue', 'search'].forEach((t) => { $(`tab-${t}`).hidden = t !== b.dataset.tab; });
    if (b.dataset.tab === 'search') $('q').focus();
    if (b.dataset.tab === 'lyrics') { lastLine = -2; userScrolledAt = 0; }
    if (b.dataset.tab === 'queue') { const r = $('queue').querySelector('.row.now'); if (r) r.scrollIntoView({ block: 'center' }); }
  }));

  document.addEventListener('keydown', (e) => {
    if ($('app').hidden || e.target instanceof HTMLInputElement || e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.code === 'Space') { e.preventDefault(); $('play').click(); }
    else if (e.key === 'ArrowRight') { e.preventDefault(); seekTo(position() + 10000); }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); seekTo(position() - 10000); }
  });

  let toastTimer = 0;
  function toast(text) {
    const t = $('toast');
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 2600);
  }

  requestAnimationFrame(frame);
  hello();
})();
