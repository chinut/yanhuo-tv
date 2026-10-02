// 端到端验证「App 里到底有没有真的在出声」。
//
// 用 Web Audio 的 AnalyserNode 直接分析 WebView 里正在播放的 <video>，
// 读取实时能量值：如果音量不为 0、画面在动，说明**声音确实在流**，
// 那问题就只在宿主（模拟器→Windows 默认播放设备）这一段。
//
// 用法：node tools/cdp-audio-energy.mjs [port]

const port = Number(process.argv[2] ?? 9222);

const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
if (!page) {
  console.log('没有可调试的页面');
  process.exit(1);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
const send = (method, params) =>
  new Promise((resolve) => {
    const msgId = ++id;
    pending.set(msgId, resolve);
    ws.send(JSON.stringify({ id: msgId, method, params }));
  });

ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
  }
});

const install = `(function(){
  var vs = document.querySelectorAll('video');
  if (!vs.length) return 'no-video';
  if (window.__dshProbe) return 'already';
  var v = null, best = -1;
  for (var i = 0; i < vs.length; i++) {
    var score = (vs[i].currentTime || 0) + (vs[i].readyState || 0) * 10;
    if (score > best) { best = score; v = vs[i]; }
  }
  try {
    var AC = window.AudioContext || window.webkitAudioContext;
    var ctx = new AC();
    var src = ctx.createMediaElementSource(v);
    var an = ctx.createAnalyser();
    an.fftSize = 2048;
    src.connect(an);
    an.connect(ctx.destination);
    window.__dshProbe = { ctx: ctx, an: an, buf: new Uint8Array(an.fftSize) };
    if (ctx.state === 'suspended') ctx.resume();
    return 'installed on ' + location.href.slice(0, 50);
  } catch (e) { return 'error:' + e.message; }
})()`;

const read = `(function(){
  var p = window.__dshProbe;
  var vs = document.querySelectorAll('video');
  var v = null, best = -1;
  // 挑「最像正在播放」的那个 video（页面可能有多个 video 元素）
  for (var i = 0; i < vs.length; i++) {
    var score = (vs[i].currentTime || 0) + (vs[i].readyState || 0) * 10;
    if (score > best) { best = score; v = vs[i]; }
  }
  var out = {
    ok: true,
    page: location.href.slice(0, 70),
    videoCount: vs.length,
    ctxState: p ? p.ctx.state : null,
    muted: v ? v.muted : null,
    volume: v ? v.volume : null,
    paused: v ? v.paused : null,
    readyState: v ? v.readyState : null,
    currentTime: v ? Math.round(v.currentTime * 10) / 10 : null,
    peak: 0,
    rms: 0
  };
  if (p) {
    p.an.getByteTimeDomainData(p.buf);
    var peak = 0, sum = 0;
    for (var j = 0; j < p.buf.length; j++) {
      var d = Math.abs(p.buf[j] - 128);
      if (d > peak) peak = d;
      sum += d * d;
    }
    out.peak = peak;
    out.rms = Math.round(Math.sqrt(sum / p.buf.length) * 100) / 100;
  }
  return JSON.stringify(out);
})()`;

ws.addEventListener('open', async () => {
  const a = await send('Runtime.evaluate', { expression: install, returnByValue: true });
  console.log('安装探针:', a?.result?.result?.value);

  // 隔 1.5 秒采样两次：两次都有能量，才算"确实在出声"
  for (let i = 0; i < 3; i++) {
    await new Promise((r) => setTimeout(r, 1500));
    const r = await send('Runtime.evaluate', { expression: read, returnByValue: true });
    const raw = r?.result?.result?.value;
    let parsed = null;
    try { parsed = JSON.parse(raw); } catch {}
    if (parsed && parsed.ok) {
      const verdict = parsed.peak > 3 && parsed.rms > 0.5 ? '有声音信号 ✅' : '基本静音 ❌';
      console.log(
        `采样${i + 1}: ${verdict}  peak=${parsed.peak} rms=${parsed.rms} ` +
          `muted=${parsed.muted} vol=${parsed.volume} paused=${parsed.paused} ` +
          `ready=${parsed.readyState} t=${parsed.currentTime} videos=${parsed.videoCount}`
      );
      console.log(`        页面: ${parsed.page}`);
    } else {
      console.log(`采样${i + 1}:`, raw);
    }
  }
  ws.close();
  process.exit(0);
});

ws.addEventListener('error', (e) => {
  console.error('WebSocket 错误:', e.message ?? e);
  process.exit(1);
});
