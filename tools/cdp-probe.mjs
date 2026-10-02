// 通过 Chrome DevTools Protocol 查询 WebView 里 <video> 的音频状态。
// 用法：node cdp-audio-probe.mjs [port] [expression]
// 不依赖第三方库，用 Node 内置的 WebSocket（Node 22+）。

const port = Number(process.argv[2] ?? 9222);
const expr = process.argv[3] ?? `(function(){
  var vs = document.querySelectorAll('video');
  var out = [];
  for (var i = 0; i < vs.length; i++) {
    var v = vs[i];
    var tracks = [];
    try {
      for (var j = 0; j < v.audioTracks.length; j++) {
        tracks.push({ enabled: v.audioTracks[j].enabled, kind: v.audioTracks[j].kind, label: v.audioTracks[j].label });
      }
    } catch (e) { tracks.push('audioTracks-error:' + e.message); }
    out.push({
      idx: i,
      muted: v.muted,
      volume: v.volume,
      paused: v.paused,
      readyState: v.readyState,
      currentTime: Math.round(v.currentTime * 10) / 10,
      videoWidth: v.videoWidth,
      videoHeight: v.videoHeight,
      audioTracks: tracks,
      src: (v.currentSrc || v.src || '').slice(0, 120)
    });
  }
  return JSON.stringify({ count: vs.length, videos: out, title: document.title });
})()`;

const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
if (!page) {
  console.error('没有找到可调试的页面');
  process.exit(1);
}
console.log('page:', page.url);

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();

function send(method, params) {
  return new Promise((resolve) => {
    const msgId = ++id;
    pending.set(msgId, resolve);
    ws.send(JSON.stringify({ id: msgId, method, params }));
  });
}

ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
  }
});

ws.addEventListener('open', async () => {
  const res = await send('Runtime.evaluate', {
    expression: expr,
    returnByValue: true,
    awaitPromise: true,
  });
  const value = res?.result?.result?.value;
  if (typeof value === 'string') {
    try {
      console.log(JSON.stringify(JSON.parse(value), null, 2));
    } catch {
      console.log(value);
    }
  } else {
    console.log(JSON.stringify(res, null, 2));
  }
  ws.close();
  process.exit(0);
});

ws.addEventListener('error', (e) => {
  console.error('WebSocket 错误:', e.message ?? e);
  process.exit(1);
});
