const API = ""; // same origin
const USER_ID = "demo";
const POLL_MS = 150;
const SESSION_KEY = "balloon_session_v1";
const SESSION_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000; // 30 days (Section 2.4 brief)
const LEADERBOARD_POLL_MS = 1000;
const MIN_WIN_AMOUNT_FOR_UPSELL = 50; // must match backend Main.MIN_WIN_AMOUNT
const UPSELL_TIMEOUT_SECONDS = 10; // matches popup.timeout.seconds default
const SOUND_PREF_KEY = "balloon_sound_enabled";
const NET_WARN_THRESHOLD = 5; // consecutive poll failures before showing the banner

// Dev mode (Section 2.3): ?seed=123 in the URL makes every round use a fixed RNG seed,
// so booster position + crash point are reproducible across sessions.
const DEV_SEED = (() => {
  try {
    const p = new URLSearchParams(window.location.search).get("seed");
    if (p == null) return null;
    const n = Number(p);
    return Number.isFinite(n) ? Math.floor(n) : null;
  } catch (e) { return null; }
})();

let state = {
  theme: "GREEN",
  levelsRed: 12,
  levelsGreen: 9,
  fragments: [],
  selectedFragment: null,
  roundId: null,
  totalLevels: 9,
  pollTimer: null,
  idleTimer: null,
  idleSeconds: 10,
  onboardingShown: localStorage.getItem("balloon_onboarding_shown") === "1",
  lastLevelsCrossed: 0,
  chirpTimer: null,
  leaderboardTimer: null,
  lbPointsByName: {},
  commitHashBeforeRound: null,
  pendingUpsell: false,
  upsellTimer: null,
  pollFailures: 0,
  boosterFlashed: {}, // guard so the flash animation only runs once per round
};

const el = (id) => document.getElementById(id);
const rand = (min, max) => Math.random() * (max - min) + min;

// ---------- Sound: separate channels so bird chirps don't block gameplay SFX ----------
let audioCtx = null;
const soundEnabled = { value: localStorage.getItem(SOUND_PREF_KEY) !== "0" };
const channelBusy = { bird: false, ui: false, game: false };

function getCtx() {
  if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  if (audioCtx.state === "suspended") audioCtx.resume();
  return audioCtx;
}
document.addEventListener("pointerdown", () => { try { getCtx(); } catch (e) {} }, { once: true });

function updateSoundButton() {
  const btn = el("btnSound");
  if (btn) btn.textContent = soundEnabled.value ? "🔊" : "🔇";
}

function playTone(channel, freqStart, freqEnd, duration, type, gainPeak) {
  if (!soundEnabled.value || channelBusy[channel]) return;
  channelBusy[channel] = true;
  try {
    const ctx = getCtx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = type || "sine";
    osc.frequency.setValueAtTime(freqStart, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(Math.max(freqEnd, 1), ctx.currentTime + duration);
    gain.gain.setValueAtTime(0.0001, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(gainPeak || 0.1, ctx.currentTime + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + duration);
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + duration + 0.02);
    osc.onended = () => { channelBusy[channel] = false; };
  } catch (e) { channelBusy[channel] = false; }
}
function playChirp()      { playTone("bird", 2400, 3200, 0.14, "sine",     0.07); }
function playWaterDrop()  { playTone("ui",    900,  200, 0.22, "sine",     0.12); }
function playLevelUp()    { playTone("ui",    650,  950, 0.13, "triangle", 0.09); }
function playExplosion() {
  if (!soundEnabled.value || channelBusy.game) return;
  channelBusy.game = true;
  try {
    const ctx = getCtx();
    const bufferSize = Math.floor(ctx.sampleRate * 0.3);
    const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < bufferSize; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / bufferSize);
    const noise = ctx.createBufferSource();
    noise.buffer = buffer;
    const gain = ctx.createGain();
    gain.gain.setValueAtTime(0.22, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.3);
    noise.connect(gain).connect(ctx.destination);
    noise.start();
    noise.onended = () => { channelBusy.game = false; };
  } catch (e) { channelBusy.game = false; }
}

// ---------- Theme screen: animated sky + floating balloons ----------
function generateSky() {
  const sky = el("skyBg");
  if (!sky) return;
  sky.innerHTML = "";
  const birdCount = Math.floor(rand(1, 4));
  const cloudCount = Math.floor(rand(1, 4));
  for (let i = 0; i < birdCount; i++) {
    const b = document.createElement("div");
    b.className = "sky-bird";
    b.textContent = "🐦";
    b.style.top = rand(5, 55) + "%";
    const dur = rand(4, 7.5);
    b.style.animationDuration = dur + "s";
    b.style.animationDelay = "-" + rand(0, dur) + "s";
    sky.appendChild(b);
  }
  for (let i = 0; i < cloudCount; i++) {
    const c = document.createElement("div");
    c.className = "sky-cloud";
    c.textContent = "☁️";
    c.style.top = rand(0, 35) + "%";
    c.style.fontSize = rand(28, 46) + "px";
    c.style.opacity = rand(0.5, 0.9);
    const dur = rand(16, 26);
    c.style.animationDuration = dur + "s";
    c.style.animationDelay = "-" + rand(0, dur) + "s";
    sky.appendChild(c);
  }
}
function floatBalloons() {
  document.querySelectorAll(".theme-card .balloon-icon").forEach((icon) => {
    const dur = rand(2.4, 3.8);
    icon.style.animationDuration = dur + "s";
    icon.style.animationDelay = "-" + rand(0, dur) + "s";
  });
}
function scheduleChirp() {
  clearTimeout(state.chirpTimer);
  const delay = rand(1800, 5000);
  state.chirpTimer = setTimeout(() => { playChirp(); scheduleChirp(); }, delay);
}

async function api(path, opts) {
  const res = await fetch(API + path, opts);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || ("HTTP_" + res.status));
  return data;
}
function form(obj) {
  return Object.entries(obj).map(([k, v]) => encodeURIComponent(k) + "=" + encodeURIComponent(v)).join("&");
}

// ---------- Network status banner ----------
function markPollSuccess() {
  if (state.pollFailures > 0) state.pollFailures = 0;
  el("netWarn").classList.add("hidden");
}
function markPollFailure() {
  state.pollFailures++;
  if (state.pollFailures >= NET_WARN_THRESHOLD) {
    el("netWarn").classList.remove("hidden");
  }
}

// ---------- Session persistence (Section 2.6) ----------
function saveSession(roundId, betAmount, boosterTier) {
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify({
      roundId, theme: state.theme, betAmount, boosterTier: boosterTier || 0, startTime: Date.now(),
    }));
  } catch (e) { /* non-fatal */ }
}
function clearSession() {
  try { localStorage.removeItem(SESSION_KEY); } catch (e) {}
}
async function tryResumeSession() {
  let saved;
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return false;
    saved = JSON.parse(raw);
  } catch (e) { clearSession(); return false; }

  const age = Date.now() - (saved.startTime || 0);
  if (!saved.roundId || age < 0 || age > SESSION_MAX_AGE_MS) { clearSession(); return false; }

  try {
    const snap = await api(`/api/round?id=${saved.roundId}`);
    if (snap.state !== "IN_PROGRESS") { clearSession(); return false; }

    state.theme = saved.theme === "RED" ? "RED" : "GREEN";
    state.totalLevels = snap.totalLevels;
    state.roundId = saved.roundId;
    state.selectedFragment = { amount: saved.betAmount, boosterTier: saved.boosterTier || 0 };
    el("betThemeLabel").textContent = (state.theme === "RED" ? "🔴 Красный шар" : "🟢 Зелёный шар") + ` · ${state.totalLevels} уровней`;
    el("betAmountLabel").textContent = saved.betAmount + " ◎";
    startGameScreen();
    return true;
  } catch (e) {
    clearSession();
    return false;
  }
}

// ---------- Screen management ----------
function showScreen(name) {
  ["Theme", "Bet", "Game"].forEach((s) => {
    el("screen" + s).classList.toggle("hidden", s !== name);
  });
  if (name === "Theme") {
    generateSky();
    floatBalloons();
    scheduleChirp();
  } else {
    clearTimeout(state.chirpTimer);
  }
  if (name !== "Game") stopLeaderboardPolling();
}

// ---------- Boot ----------
async function refreshUserState() {
  const s = await api(`/api/state?userId=${USER_ID}`);
  el("balanceVal").textContent = s.balance.toFixed(0);
  el("pointsVal").textContent = s.points;
  el("redLevels").textContent = s.levelsRed;
  el("greenLevels").textContent = s.levelsGreen;
  state.levelsRed = s.levelsRed;
  state.levelsGreen = s.levelsGreen;
  state.fragments = s.fragments;
  return s;
}

async function loadHistory() {
  const data = await api(`/api/history?userId=${USER_ID}`);
  const list = el("historyList");
  list.innerHTML = "";
  if (!data.history.length) {
    list.innerHTML = '<div class="history-row">Пока нет завершённых игр</div>';
    return;
  }
  data.history.forEach((h) => {
    const row = document.createElement("div");
    row.className = "history-row " + (h.state === "CASHED_OUT" ? "win" : "loss");
    const theme = h.theme === "RED" ? "🔴" : "🟢";
    const result = h.state === "CASHED_OUT" ? `Забрал ×${h.resultMultiplier.toFixed(2)}` : `Crash ×${h.resultMultiplier.toFixed(2)}`;
    row.innerHTML = `<span>${theme} ставка ${h.betAmount}</span><span>${result} · +${h.pointsEarned} очк.</span>`;
    list.appendChild(row);
  });
}

// ---------- Live leaderboard strip (Section 2.1) ----------
async function renderLeaderboard() {
  let data;
  try {
    data = await api(`/api/leaderboard?userId=${USER_ID}`);
  } catch (e) {
    return;
  }
  const strip = el("leaderboardStrip");
  if (!strip) return;
  strip.innerHTML = "";
  data.leaderboard.forEach((p) => {
    const item = document.createElement("div");
    item.className = "lb-item" + (p.isMe ? " me" : "");
    item.innerHTML = `<div class="lb-name">${p.rank}. ${p.name}</div><div class="lb-points">${p.points}</div>`;
    const prev = state.lbPointsByName[p.name];
    if (prev !== undefined && prev !== p.points) item.classList.add("flash");
    state.lbPointsByName[p.name] = p.points;
    strip.appendChild(item);
  });
}
function startLeaderboardPolling() {
  stopLeaderboardPolling();
  renderLeaderboard();
  state.leaderboardTimer = setInterval(renderLeaderboard, LEADERBOARD_POLL_MS);
}
function stopLeaderboardPolling() {
  clearInterval(state.leaderboardTimer);
  state.leaderboardTimer = null;
}

function renderFragments() {
  const wrap = el("fragments");
  wrap.innerHTML = "";
  const balance = parseFloat(el("balanceVal").textContent);
  state.fragments.forEach((f) => {
    const btn = document.createElement("button");
    const affordable = balance >= f.amount;
    btn.className = "fragment" + (!affordable ? " disabled" : "");
    btn.innerHTML = `<div class="amt">${f.amount} ◎</div><div class="boost">${f.boosterTier === 0 ? "Без бустера" : "Бустер ×" + f.boosterTier}</div>`;
    btn.onclick = () => {
      if (!affordable) {
        alert("Не хватает бонусов");
        return;
      }
      state.selectedFragment = f;
      document.querySelectorAll(".fragment").forEach((n) => n.classList.remove("selected"));
      btn.classList.add("selected");
      el("btnStart").disabled = false;
    };
    wrap.appendChild(btn);
  });
}

// ---------- Theme -> Bet ----------
document.querySelectorAll(".theme-card").forEach((card) => {
  card.onclick = async () => {
    playWaterDrop();
    state.theme = card.dataset.theme;
    state.totalLevels = state.theme === "RED" ? state.levelsRed : state.levelsGreen;
    el("betThemeLabel").textContent = (state.theme === "RED" ? "🔴 Красный шар" : "🟢 Зелёный шар") + ` · ${state.totalLevels} уровней`;
    state.selectedFragment = null;
    el("btnStart").disabled = true;
    await refreshUserState();
    renderFragments();
    await loadHistory();
    showScreen("Bet");
  };
});
el("btnBackToTheme").onclick = () => showScreen("Theme");

// ---------- Start round ----------
el("btnStart").onclick = async () => {
  if (!state.selectedFragment) return;
  el("btnStart").disabled = true;
  try {
    const betForm = {
      userId: USER_ID,
      theme: state.theme,
      betAmount: state.selectedFragment.amount,
      boosterTier: state.selectedFragment.boosterTier,
    };
    if (DEV_SEED != null) betForm.seed = DEV_SEED;
    const res = await api("/api/bet", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form(betForm),
    });
    state.roundId = res.roundId;
    el("balanceVal").textContent = res.balance.toFixed(0);
    el("betAmountLabel").textContent = res.betAmount + " ◎";
    state.commitHashBeforeRound = res.commitHash || null;
    saveSession(res.roundId, res.betAmount, res.boosterTier);
    startGameScreen();
  } catch (e) {
    alert("Ошибка ставки: " + e.message);
    el("btnStart").disabled = false;
  }
};

// ---------- Game screen ----------
function buildTrack() {
  const track = el("track");
  track.innerHTML = "";
  const n = state.totalLevels;
  for (let i = 1; i <= n; i++) {
    const line = document.createElement("div");
    line.className = "level-line";
    line.style.bottom = (i / (n + 1)) * 100 + "%";
    line.id = "level-" + i;
    track.appendChild(line);
  }
}

function startGameScreen() {
  buildTrack();
  el("balloonSprite").style.bottom = "6%";
  el("balloonSprite").classList.remove("crashed", "popping");
  el("multiplierDisplay").className = "multiplier";
  el("multiplierDisplay").textContent = "1.00x";
  el("btnCashout").disabled = true;
  el("btnCashout").textContent = "ЗАБРАТЬ";
  el("cashedOutBanner").classList.add("hidden");
  el("potentialWin").textContent = "—";
  el("fairnessBlock").classList.add("hidden");
  state.lastLevelsCrossed = 0;
  state.pendingUpsell = false;
  state.boosterFlashed = {};
  showScreen("Game");
  startLeaderboardPolling();

  if (!state.onboardingShown) {
    const tip = el("onboardingTip");
    tip.classList.remove("hidden");
    void tip.offsetWidth;
    tip.classList.add("show");
    setTimeout(() => {
      tip.classList.remove("show");
      setTimeout(() => tip.classList.add("hidden"), 500);
    }, 4000);
    localStorage.setItem("balloon_onboarding_shown", "1");
    state.onboardingShown = true;
  }

  clearInterval(state.pollTimer);
  state.pollTimer = setInterval(pollRound, POLL_MS);
  pollRound();
}

function multiplierTierClass(levelsCrossed) {
  if (levelsCrossed <= 0) return "";
  if (levelsCrossed === 1) return "tier1";
  if (levelsCrossed === 2) return "tier2";
  return "tier3";
}

async function pollRound() {
  if (!state.roundId) return;
  let snap;
  try {
    snap = await api(`/api/round?id=${state.roundId}`);
    markPollSuccess();
  } catch (e) {
    markPollFailure();
    return;
  }

  const mult = snap.displayMultiplier;
  el("multiplierDisplay").textContent = mult.toFixed(2) + "x";
  el("multiplierDisplay").className = "multiplier " + multiplierTierClass(snap.levelsCrossed);
  el("potentialWin").textContent = (parseFloat(el("betAmountLabel").textContent) * mult).toFixed(0) + " ◎";

  if (snap.levelsCrossed > state.lastLevelsCrossed) {
    state.lastLevelsCrossed = snap.levelsCrossed;
    playLevelUp();
    showPointsPopup("+" + (snap.levelsCrossed * 10));
    const lineEl = el("level-" + snap.levelsCrossed);
    if (lineEl) lineEl.style.background = "rgba(47,143,78,0.6)";
  }
  // Booster: pending -> active -> used. Track each transition once per round.
  if (snap.boosterLevelIndex > 0) {
    const boosterLine = el("level-" + snap.boosterLevelIndex);
    if (boosterLine) {
      // 1) Pending state: render once as soon as we know the level exists.
      if (!boosterLine.dataset.pending) {
        boosterLine.dataset.pending = "1";
        boosterLine.classList.add("booster");
        const badge = document.createElement("span");
        badge.className = "booster-badge";
        badge.textContent = "×" + (snap.boosterTier || "");
        boosterLine.appendChild(badge);
      }

      // 2) Active state: entered when snap.boosterActive flips true for the first time.
      if (snap.boosterActive && !state.boosterFlashed[snap.boosterLevelIndex]) {
        state.boosterFlashed[snap.boosterLevelIndex] = true;
        boosterLine.classList.add("booster-active");

        // Big label
        const popup = document.createElement("div");
        popup.className = "booster-popup";
        popup.textContent = "БУСТЕР ×" + (snap.boosterTier || "") + "!";
        popup.style.bottom = boosterLine.style.bottom;
        el("trackWrap").appendChild(popup);
        setTimeout(() => popup.remove(), 1700);

        // Points popup for the booster bonus (uses the same 25 the backend awards)
        const pts = document.createElement("div");
        pts.className = "booster-points-popup";
        pts.textContent = "+25 очков";
        pts.style.bottom = `calc(${boosterLine.style.bottom} - 24px)`;
        el("trackWrap").appendChild(pts);
        setTimeout(() => pts.remove(), 1500);
      }

      // 3) Used state: after cashout, mark the line grey.
      if (snap.state === "CASHED_OUT" && !boosterLine.dataset.used) {
        boosterLine.dataset.used = "1";
        boosterLine.classList.remove("booster-active");
        boosterLine.classList.add("booster-used");
      }
    }
  }

  const progress = Math.min(1, Math.log(mult) / Math.log(Math.max(2, snap.totalLevels)));
  el("balloonSprite").style.bottom = (6 + progress * 82) + "%";

  el("btnCashout").disabled = !snap.canCashout;

  if (snap.state === "CASHED_OUT" && !el("cashedOutBanner").dataset.shown) {
    el("cashedOutBanner").classList.remove("hidden");
    el("cashedOutBanner").dataset.shown = "1";
    el("btnCashout").disabled = true;
    el("btnCashout").textContent = `Забрано ×${snap.cashoutMultiplier.toFixed(2)}`;
  }

  if (snap.justCrashedNow) {
    triggerCrashAnimation();
    clearInterval(state.pollTimer);
    setTimeout(() => finishRound(snap), 500);
    return;
  }

  if (snap.state !== "IN_PROGRESS" && (snap.crashRevealed || snap.state === "CRASHED")) {
    clearInterval(state.pollTimer);
    finishRound(snap);
  }
}

function triggerCrashAnimation() {
  const sprite = el("balloonSprite");
  playExplosion();
  sprite.classList.add("popping");

  const burst = document.createElement("div");
  burst.className = "burst";
  burst.style.left = "50%";
  burst.style.bottom = sprite.style.bottom || "6%";
  el("trackWrap").appendChild(burst);
  setTimeout(() => burst.remove(), 650);

  setTimeout(() => {
    sprite.classList.remove("popping");
    sprite.classList.add("crashed");
  }, 350);
}

function showPointsPopup(text) {
  const p = el("pointsPopup");
  p.textContent = text;
  p.classList.remove("show");
  void p.offsetWidth;
  p.classList.add("show");
}

el("btnCashout").onclick = async () => {
  el("btnCashout").disabled = true;
  try {
    const resp = await api("/api/cashout", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form({ id: state.roundId }),
    });
    if (resp && resp.showUpsell) state.pendingUpsell = true;
  } catch (e) {
    // Too late / already resolved -- next poll will reflect real state.
  }
};

// ---------- Result ----------
function rewardLabel(code) {
  const map = {
    puzzle_piece_1: "🧩 Фрагмент пазла №1",
    puzzle_piece_2: "🧩 Фрагмент пазла №2",
    puzzle_piece_3: "🧩 Фрагмент пазла №3",
    puzzle_piece_4: "🧩 Фрагмент пазла №4",
  };
  return map[code] || code;
}

function finishRound(snap) {
  clearSession();
  stopLeaderboardPolling();
  el("cashedOutBanner").dataset.shown = "";
  const win = snap.state === "CASHED_OUT";
  el("resultTitle").textContent = win ? "🏆 Победа!" : "💥 Шар лопнул";
  el("resultTitle").style.color = win ? "#1f9f56" : "#c9433a";

  if (win) {
    el("resultBody").innerHTML = `
      Выигрыш: <b>${snap.payout.toFixed(0)} ◎</b> (×${snap.cashoutMultiplier.toFixed(2)})<br>
      Крах произошёл на ×${snap.crashMultiplier.toFixed(2)} — потенциальный максимум был ${(parseFloat(el("betAmountLabel").textContent) * snap.crashMultiplier).toFixed(0)} ◎<br>
      Очки за раунд: <b>+${snap.pointsEarned}</b><br>
      Награда: ${rewardLabel(snap.reward)}`;
  } else {
    el("resultBody").innerHTML = `
      Ставка сгорела. Крах на ×${snap.crashMultiplier.toFixed(2)}<br>
      Очки за раунд: <b>+${snap.pointsEarned}</b><br>
      Награда: ${rewardLabel(snap.reward)}`;
  }
  el("balanceVal").textContent = snap.balance.toFixed(0);
  el("pointsVal").textContent = snap.totalPoints;

  el("resultModal").classList.remove("hidden");
  startIdleCountdown();
  refreshRewardProgress();
  renderFairnessCheck(snap);

  if (win && (snap.showUpsell || state.pendingUpsell)) {
    setTimeout(() => openUpsellPopup(snap), 600);
  }
  state.pendingUpsell = false;
  state.commitHashBeforeRound = null;
}

const PUZZLE_POOL_SIZE = 4;
async function refreshRewardProgress() {
  try {
    const s = await api(`/api/state?userId=${USER_ID}`);
    const collected = s.rewards ? Object.keys(s.rewards).length : 0;
    const box = el("rewardProgress");
    box.classList.remove("hidden");
    box.innerHTML = `
      <div class="progress-label">Пазл собран: ${collected} из ${PUZZLE_POOL_SIZE}</div>
      <div class="progress-bar"><div class="progress-fill" style="width:${(collected / PUZZLE_POOL_SIZE) * 100}%"></div></div>`;
  } catch (e) {
    el("rewardProgress").classList.add("hidden");
  }
}

// ---------- Provably-fair verify (Section 2.4) ----------
async function renderFairnessCheck(snap) {
  const block = el("fairnessBlock");
  const content = el("fairnessContent");
  const before = state.commitHashBeforeRound;
  if (!snap.serverSeed || !before || !snap.crashMultiplierStr) {
    block.classList.add("hidden");
    return;
  }
  block.classList.remove("hidden");
  content.innerHTML = `
    <div class="fh-row"><b>commitHash</b> (получен ДО старта раунда): ${before}</div>
    <div class="fh-row"><b>serverSeed</b> (раскрыт после раунда): ${snap.serverSeed}</div>
    <div class="fh-row"><b>Точка краха:</b> ×${snap.crashMultiplierStr}</div>
    <div class="fh-row" id="fairnessResult">Пересчитываем SHA-256…</div>`;
  try {
    const input = snap.serverSeed + ":" + snap.crashMultiplierStr;
    const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
    const hex = Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
    const match = hex === before;
    el("fairnessResult").innerHTML = match
      ? `<span class="fh-ok">✔ SHA-256(seed + ":" + crash) совпадает с commitHash, полученным до старта раунда — раунд честный</span>`
      : `<span class="fh-bad">✘ Хеш НЕ совпадает</span>`;
  } catch (e) {
    el("fairnessResult").textContent = "crypto.subtle недоступен в этом браузере";
  }
}

function startIdleCountdown() {
  state.idleSeconds = 10;
  el("idleCounter").textContent = state.idleSeconds;
  clearInterval(state.idleTimer);
  state.idleTimer = setInterval(() => {
    state.idleSeconds--;
    el("idleCounter").textContent = state.idleSeconds;
    if (state.idleSeconds <= 0) {
      clearInterval(state.idleTimer);
      goPlayAgain();
    }
  }, 1000);
}
function stopIdleCountdown() { clearInterval(state.idleTimer); }

async function goPlayAgain() {
  stopIdleCountdown();
  el("resultModal").classList.add("hidden");
  state.roundId = null;
  await refreshUserState();
  renderFragments();
  await loadHistory();
  state.selectedFragment = null;
  el("btnStart").disabled = true;
  showScreen("Bet");
}
el("btnPlayAgain").onclick = goPlayAgain;
el("btnRepeatSame").onclick = async () => {
  if (!state.selectedFragment) { goPlayAgain(); return; }
  stopIdleCountdown();
  el("resultModal").classList.add("hidden");
  try {
    const betForm = {
      userId: USER_ID,
      theme: state.theme,
      betAmount: state.selectedFragment.amount,
      boosterTier: state.selectedFragment.boosterTier,
    };
    if (DEV_SEED != null) betForm.seed = DEV_SEED;
    const res = await api("/api/bet", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form(betForm),
    });
    state.roundId = res.roundId;
    el("balanceVal").textContent = res.balance.toFixed(0);
    el("betAmountLabel").textContent = res.betAmount + " ◎";
    state.commitHashBeforeRound = res.commitHash || null;
    saveSession(res.roundId, res.betAmount, res.boosterTier);
    startGameScreen();
  } catch (e) {
    alert("Ошибка ставки: " + e.message);
    goPlayAgain();
  }
};
el("btnHome").onclick = async () => {
  stopIdleCountdown();
  el("resultModal").classList.add("hidden");
  state.roundId = null;
  showScreen("Theme");
};
el("btnCloseResult").onclick = () => { goPlayAgain(); };

// ---------- Rules modal ----------
el("btnRules").onclick = () => el("rulesModal").classList.remove("hidden");
el("btnCloseRules").onclick = () => el("rulesModal").classList.add("hidden");

// ---------- Tournament table (Section 1.7) ----------
async function refreshTournamentTimer() {
  try {
    const data = await api(`/api/tournament?userId=${USER_ID}`);
    const days = Math.max(0, Math.floor(data.hoursLeft / 24));
    el("tournamentDaysLeft").textContent = days;
    el("floatingTournamentTimer").textContent = Math.floor(data.hoursLeft) + "ч";
  } catch (e) { /* non-fatal */ }
}

async function openTournamentModal() {
  let data;
  try {
    data = await api(`/api/tournament?userId=${USER_ID}`);
  } catch (e) {
    alert("Не удалось загрузить турнирную таблицу");
    return;
  }
  el("tournamentHoursLeft").textContent = data.hoursLeft.toFixed(1);
  el("tournamentMyRank").textContent = data.myRank > 0 ? ("#" + data.myRank + " из " + data.totalPlayers) : "—";

  const top3 = data.ranking.slice(0, 3);
  const medals = ["🥇", "🥈", "🥉"];
  el("tournamentTopThree").innerHTML = top3.map((p, i) => `
    <div class="top3-item">
      <div class="t3-rank">${medals[i] || (i + 1)}</div>
      <div class="t3-name">${p.name}</div>
      <div class="t3-points">${p.points} 🏆</div>
    </div>`).join("");

  const rest = data.ranking.slice(3);
  const list = el("tournamentList");
  list.innerHTML = "";
  rest.forEach((p) => {
    const row = document.createElement("div");
    row.className = "tournament-row" + (p.isMe ? " me" : "");
    row.innerHTML = `<span class="t-rank">${p.rank}</span><span class="t-name">${p.name}${p.isMe ? " (вы)" : ""}</span><span class="t-points">${p.points} 🏆</span>`;
    list.appendChild(row);
  });

  el("tournamentModal").classList.remove("hidden");
}
el("btnOpenTournamentCard").onclick = openTournamentModal;
el("btnFloatingTournament").onclick = openTournamentModal;
el("btnCloseTournament").onclick = () => el("tournamentModal").classList.add("hidden");

// ---------- Upsell popup "Закрепи успех" (Section 1.8) ----------
function openUpsellPopup(snap) {
  const balance = parseFloat(el("balanceVal").textContent) || 0;
  const bet = snap.betAmount || 0;
  const tickets = Math.max(1, Math.min(10, Math.round(snap.payout / 20)));
  const price = Math.max(5, Math.round(tickets * (bet / 10 || 5)));
  const affordable = balance >= price;

  el("upsellBody").innerHTML = `
    Вы выиграли <b>${snap.payout.toFixed(0)} ◎</b>! Закрепите успех:
    обменяйте <b>${price}</b> бонусных баллов на <b>${tickets}</b> билет${tickets === 1 ? "" : "а"} лотереи.<br>
    <small>Ваш баланс: ${balance.toFixed(0)} ◎${affordable ? "" : " — не хватает баллов"}</small>`;
  const buyBtn = el("btnBuyTickets");
  buyBtn.disabled = !affordable;
  buyBtn.textContent = affordable ? `Купить за ${price} баллов` : "Не хватает баллов";
  buyBtn.onclick = async () => {
    buyBtn.disabled = true;
    try {
      const res = await api("/api/buyTickets", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: form({ userId: USER_ID, tickets, price }),
      });
      el("balanceVal").textContent = res.balance.toFixed(0);
      if (res.points !== undefined) el("pointsVal").textContent = res.points;
      buyBtn.textContent = `Куплено: ${res.ticketsOwned} билетов ✓`;
      clearInterval(state.upsellTimer);
      setTimeout(closeUpsellPopup, 900);
    } catch (e) {
      buyBtn.disabled = false;
      alert("Не удалось купить билеты: " + e.message);
    }
  };

  el("upsellModal").classList.remove("hidden");
  startUpsellCountdown();
}
function closeUpsellPopup() {
  clearInterval(state.upsellTimer);
  el("upsellModal").classList.add("hidden");
}
function startUpsellCountdown() {
  let seconds = UPSELL_TIMEOUT_SECONDS;
  el("upsellCountdown").textContent = seconds;
  clearInterval(state.upsellTimer);
  state.upsellTimer = setInterval(() => {
    seconds--;
    el("upsellCountdown").textContent = seconds;
    if (seconds <= 0) closeUpsellPopup();
  }, 1000);
}
el("btnCloseUpsell").onclick = closeUpsellPopup;

// ---------- Admin / config modal ----------
const CONFIG_FIELDS = [
  ["pointsPerLine", "Очки за уровень"],
  ["pointsBoosterBonus", "Бонус очков за бустер"],
  ["pointsCashoutBonus", "Бонус очков за cashout"],
  ["growthRate", "Скорость роста коэфф."],
  ["houseEdge", "House edge (0..1)"],
  ["minCrashMultiplier", "Мин. коэфф. краха"],
  ["maxMultiplier", "Макс. коэфф."],
  ["levelStep", "Шаг уровня"],
  ["boosterTier2Value", "Бустер ×2 — значение"],
  ["boosterTier3Value", "Бустер ×3 — значение"],
  ["boosterTier4Value", "Бустер ×4 — значение"],
];
const PROP_KEY = {
  pointsPerLine: "points.per.line",
  pointsBoosterBonus: "points.booster.bonus",
  pointsCashoutBonus: "points.cashout.bonus",
  growthRate: "growth.rate",
  houseEdge: "house.edge",
  minCrashMultiplier: "min.crash.multiplier",
  maxMultiplier: "max.multiplier",
  levelStep: "level.step",
  boosterTier2Value: "booster.tier2.value",
  boosterTier3Value: "booster.tier3.value",
  boosterTier4Value: "booster.tier4.value",
};

const VALIDATION_RULES = {
  pointsPerLine:        { type: "int",   min: 0,                          label: "Допустимо: целое ≥ 0" },
  pointsBoosterBonus:   { type: "int",   min: 0,                          label: "Допустимо: целое ≥ 0" },
  pointsCashoutBonus:   { type: "int",   min: 0,                          label: "Допустимо: целое ≥ 0" },
  growthRate:           { type: "float", min: 0.05, max: 0.5,             label: "Допустимо: 0.05–0.5" },
  houseEdge:            { type: "float", min: 0.00, max: 0.20,            label: "Допустимо: 0.00–0.20" },
  minCrashMultiplier:   { type: "float", min: 1.00,                       label: "Допустимо: ≥ 1.00" },
  maxMultiplier:        { type: "float", greaterThanField: "minCrashMultiplier", label: "Допустимо: больше «Макс. коэфф.» мин. краха" },
  levelStep:            { type: "float", min: 0.05, max: 0.5,             label: "Допустимо: 0.05–0.5" },
  boosterTier2Value:    { type: "float", min: 1, exclusiveMin: true,      label: "Допустимо: > 1" },
  boosterTier3Value:    { type: "float", min: 1, exclusiveMin: true,      label: "Допустимо: > 1" },
  boosterTier4Value:    { type: "float", min: 1, exclusiveMin: true,      label: "Допустимо: > 1" },
};

el("btnAdmin").onclick = async () => {
  const cfg = await api("/api/config");
  const formEl = el("adminForm");
  formEl.innerHTML = "";
  CONFIG_FIELDS.forEach(([key, label]) => {
    const wrap = document.createElement("label");
    wrap.innerHTML = `${label} <input type="number" step="any" data-key="${key}" value="${cfg[key]}"><div class="field-error hidden"></div>`;
    formEl.appendChild(wrap);
  });
  el("adminSaved").classList.add("hidden");
  el("adminModal").classList.remove("hidden");
};
el("btnCloseAdmin").onclick = () => el("adminModal").classList.add("hidden");

function validateAdminForm() {
  const inputs = document.querySelectorAll("#adminForm input");
  const values = {};
  inputs.forEach((i) => { values[i.dataset.key] = i.value.trim(); });

  let allValid = true;
  inputs.forEach((i) => {
    const key = i.dataset.key;
    const rule = VALIDATION_RULES[key];
    const errorEl = i.parentElement.querySelector(".field-error");
    const raw = values[key];
    const num = Number(raw);
    let msg = "";

    if (raw === "" || Number.isNaN(num)) {
      msg = "Введите число";
    } else if (rule.type === "int" && !Number.isInteger(num)) {
      msg = "Допустимо: целое число";
    } else if (rule.greaterThanField) {
      const other = Number(values[rule.greaterThanField]);
      if (Number.isNaN(other) || num <= other) msg = rule.label;
    } else if (rule.exclusiveMin && num <= rule.min) {
      msg = rule.label;
    } else if (rule.min !== undefined && !rule.exclusiveMin && num < rule.min) {
      msg = rule.label;
    } else if (rule.max !== undefined && num > rule.max) {
      msg = rule.label;
    }

    if (msg) {
      allValid = false;
      errorEl.textContent = msg;
      errorEl.classList.remove("hidden");
    } else {
      errorEl.textContent = "";
      errorEl.classList.add("hidden");
    }
  });
  return allValid;
}

el("btnSaveAdmin").onclick = async () => {
  el("adminSaved").classList.add("hidden");
  if (!validateAdminForm()) return;

  const inputs = document.querySelectorAll("#adminForm input");
  const payload = {};
  inputs.forEach((i) => { payload[PROP_KEY[i.dataset.key]] = i.value; });
  await api("/api/config", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form(payload),
  });
  el("adminSaved").classList.remove("hidden");
};

// ---------- Sound toggle ----------
if (el("btnSound")) {
  updateSoundButton();
  el("btnSound").onclick = () => {
    soundEnabled.value = !soundEnabled.value;
    localStorage.setItem(SOUND_PREF_KEY, soundEnabled.value ? "1" : "0");
    updateSoundButton();
  };
}

// ---------- Keyboard shortcuts ----------
// Space / Enter = cashout on the game screen, Enter = start on the bet screen, Esc = close modals.
document.addEventListener("keydown", (e) => {
  // Ignore if user is typing in an input
  if (e.target && (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA")) return;

  if (e.code === "Escape") {
    ["resultModal", "rulesModal", "adminModal", "tournamentModal", "upsellModal"].forEach((id) => {
      const m = el(id);
      if (m && !m.classList.contains("hidden")) m.classList.add("hidden");
    });
    return;
  }

  const onGame = !el("screenGame").classList.contains("hidden");
  const onBet  = !el("screenBet").classList.contains("hidden");

  if (onGame && (e.code === "Space" || e.code === "Enter")) {
    if (!el("btnCashout").disabled) {
      e.preventDefault();
      el("btnCashout").click();
    }
    return;
  }
  if (onBet && e.code === "Enter" && !el("btnStart").disabled) {
    e.preventDefault();
    el("btnStart").click();
  }
});

// ---------- init ----------
(async function init() {
  if (DEV_SEED != null) console.info("[dev] fixed seed active: " + DEV_SEED);
  await refreshUserState();
  const resumed = await tryResumeSession();
  if (!resumed) showScreen("Theme");
  refreshTournamentTimer();
})();