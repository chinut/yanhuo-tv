// 逐个台检查真实播放状态：读 WebView 里 <video> 的关键指标。
// 用法：node tools/cdp-audio-probe.mjs [port]
const port = Number(process.argv[2] ?? 9222);

const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
if (!page) {
  console.log(JSON.stringify({ ok: false, reason: 'no-page' }));
  process.exit(0);
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

ws.addEventListener('open', async () => {
  const expr = `(function(){
    var v = document.querySelector('video');
    return JSON.stringify({
      url: location.href.slice(0, 60),
      hasVideo: !!v,
      muted: v ? v.muted : null,
      volume: v ? v.volume : null,
      paused: v ? v.paused : null,
      readyState: v ? v.readyState : null,
      w: v ? v.videoWidth : 0,
      t: v ? Math.round(v.currentTime * 10) / 10 : 0
    });
  })()`;
  const res = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
  const value = res?.result?.result?.value;
  console.log(value ?? JSON.stringify({ ok: false, raw: res }));
  ws.close();
  process.exit(0);
});
ws.addEventListener('error', () => process.exit(1));
