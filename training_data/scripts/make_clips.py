#!/usr/bin/env python3
"""
Extract the last N minutes of selected training WAVs, compress to MP3 with
ffmpeg, compute amplitude / crack-band envelopes, and emit a standalone
HTML player (waveform + roast-time readout + click-to-log) into
training_data/clips/.  Open clips/player.html in a browser to listen.
"""
import json
import shutil
import subprocess
import wave
from pathlib import Path

import numpy as np

FFMPEG = shutil.which("ffmpeg") or r"C:\Users\User\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1.1-full_build\bin\ffmpeg.exe"

RAW = Path(__file__).parent.parent / "raw"
CLIPS = Path(__file__).parent.parent / "clips"
CLIPS.mkdir(exist_ok=True)

HTML_TEMPLATE = r"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RoastCompanion — crack listening</title>
<style>
  :root{--bg:#150D08;--card:#241710;--border:#382519;--text:#FBF4E8;--muted:#A89178;
        --dim:#7A5B40;--amber:#FF9544;--red:#E84A3A;--mint:#58FFA9;}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--text);
       font-family:-apple-system,Segoe UI,Roboto,sans-serif;padding:20px}
  h1{font-size:18px;margin:0 0 4px}
  .sub{color:var(--muted);font-size:13px;margin-bottom:20px}
  .card{background:var(--card);border:1px solid var(--border);border-radius:18px;
        padding:18px;margin-bottom:22px}
  .row{display:flex;align-items:center;gap:14px;flex-wrap:wrap;margin-bottom:12px}
  .name{font-weight:600;font-size:15px}
  .meta{color:var(--dim);font-size:12px;font-family:monospace}
  canvas{width:100%;height:130px;display:block;border-radius:10px;background:#0F0905;cursor:crosshair}
  .clock{font-family:monospace;font-size:26px;color:var(--amber);font-weight:600}
  .legend{display:flex;gap:16px;font-size:11px;color:var(--muted);margin-top:8px;flex-wrap:wrap}
  .swatch{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:5px;vertical-align:middle}
  audio{width:100%;margin-top:12px;filter:invert(.92) hue-rotate(180deg)}
  button{background:var(--amber);color:#1F0900;border:0;border-radius:9px;padding:9px 14px;
         font-weight:600;cursor:pointer;font-size:13px}
  button.ghost{background:transparent;color:var(--amber);border:1px solid var(--amber)}
  .marks{margin-top:10px;font-family:monospace;font-size:12px;color:var(--mint);min-height:18px;white-space:pre-wrap}
  .hint{color:var(--dim);font-size:11px;margin-top:6px}
</style></head><body>
<h1>RoastCompanion — crack listening</h1>
<div class="sub">Last 3 minutes of each roast. Click the waveform to seek. Press
<b>C</b> (or the button) while listening to log the exact roast-time of a crack edge.</div>
<div id="root"></div>
<script>
const DATA = /*DATA*/;
const fmt = s => { s=Math.max(0,s); const m=Math.floor(s/60),x=Math.floor(s%60);
  return m+":"+String(x).padStart(2,"0"); };
const kindColor = k => k==="you" ? "#58FFA9" : "#E84A3A";

DATA.forEach((d,idx)=>{
  const card=document.createElement("div"); card.className="card";
  card.innerHTML=`
    <div class="row">
      <span class="name">${d.name}</span>
      <span class="meta">clip = roast ${fmt(d.clipStartSec)}–${fmt(d.totalSec)}  ·  ${d.mp3}</span>
      <span style="flex:1"></span>
      <span class="clock" id="clk${idx}">${fmt(d.clipStartSec)}</span>
    </div>
    <canvas id="cv${idx}" width="1600" height="260"></canvas>
    <div class="legend">
      <span><span class="swatch" style="background:#FF9544"></span>loudness</span>
      <span><span class="swatch" style="background:#58a9ff"></span>crack-band (2–9 kHz)</span>
      <span><span class="swatch" style="background:#58FFA9"></span>your confirmed mark</span>
      <span><span class="swatch" style="background:#E84A3A"></span>app auto-detect</span>
    </div>
    <audio id="au${idx}" src="${d.mp3}" controls preload="metadata"></audio>
    <div class="row" style="margin-top:12px">
      <button id="logFC${idx}">Log FC edge (C)</button>
      <button class="ghost" id="logSC${idx}">Log SC edge</button>
      <button class="ghost" id="clr${idx}">Clear</button>
    </div>
    <div class="marks" id="mk${idx}"></div>
    <div class="hint">Tip: scrub to where the popping clearly starts, hit “Log FC edge”; do the same at the end and at SC start. Read the green times back to me.</div>`;
  document.getElementById("root").appendChild(card);

  const au=card.querySelector(`#au${idx}`), cv=card.querySelector(`#cv${idx}`),
        ctx=cv.getContext("2d"), clk=card.querySelector(`#clk${idx}`),
        mk=card.querySelector(`#mk${idx}`);
  const W=cv.width,H=cv.height, n=d.rms.length, dur=d.clipDurSec;
  let logs=[];

  function draw(){
    ctx.clearRect(0,0,W,H);
    // crack-band lane (top, blue) + loudness lane (bottom, amber)
    for(let i=0;i<n;i++){
      const x=i/n*W;
      const ch=d.crk[i]*H*0.9;
      ctx.fillStyle="rgba(88,169,255,.55)";
      ctx.fillRect(x,0,W/n+0.5,ch*0.5);
      const rh=d.rms[i]*H*0.55;
      ctx.fillStyle="rgba(255,149,68,.8)";
      ctx.fillRect(x,H-rh,W/n+0.5,rh);
    }
    // event markers (roast-time -> clip-time -> x)
    d.markers.forEach(m=>{
      const ct=m.roastSec-d.clipStartSec; if(ct<0||ct>dur)return;
      const x=ct/dur*W; ctx.strokeStyle=kindColor(m.kind); ctx.lineWidth=2;
      ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,H);ctx.stroke();
      ctx.fillStyle=kindColor(m.kind);ctx.font="11px monospace";
      ctx.fillText(m.label,x+3,m.kind==="you"?14:H-6);
    });
    // your logged edges (white)
    logs.forEach(l=>{const x=(l-d.clipStartSec)/dur*W;
      ctx.strokeStyle="#fff";ctx.lineWidth=1;ctx.setLineDash([4,3]);
      ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,H);ctx.stroke();ctx.setLineDash([]);});
    // playhead
    const px=(au.currentTime/dur)*W;
    ctx.strokeStyle="#fff";ctx.lineWidth=2;ctx.beginPath();
    ctx.moveTo(px,0);ctx.lineTo(px,H);ctx.stroke();
  }
  function tick(){clk.textContent=fmt(d.clipStartSec+au.currentTime);draw();
    if(!au.paused)requestAnimationFrame(tick);}
  au.addEventListener("play",tick);
  au.addEventListener("timeupdate",()=>{if(au.paused){clk.textContent=fmt(d.clipStartSec+au.currentTime);draw();}});
  au.addEventListener("seeked",()=>{clk.textContent=fmt(d.clipStartSec+au.currentTime);draw();});
  cv.addEventListener("click",e=>{const r=cv.getBoundingClientRect();
    au.currentTime=Math.max(0,Math.min(dur,(e.clientX-r.left)/r.width*dur));});

  function log(kind){const rt=d.clipStartSec+au.currentTime; logs.push(rt);
    mk.textContent += `${kind} @ roast ${fmt(rt)}  (${rt.toFixed(1)}s)\n`; draw();}
  card.querySelector(`#logFC${idx}`).onclick=()=>log("FC");
  card.querySelector(`#logSC${idx}`).onclick=()=>log("SC");
  card.querySelector(`#clr${idx}`).onclick=()=>{logs=[];mk.textContent="";draw();};
  au.addEventListener("keydown",e=>{if(e.key==="c")log("FC");});
  document.addEventListener("keydown",e=>{if(e.key==="c"&&document.activeElement===au)log("FC");});
  draw();
});
document.addEventListener("keydown",e=>{ if(e.key==="c"){
  const au=document.querySelector("audio:not([paused])"); } });
</script></body></html>"""

CLIP_MIN = 3.0
ENV_HZ   = 10
SR       = 44100
N_FFT    = 2048
HF_LOW, HF_HIGH, FLOOR = 2000, 9000, 150

# (startMs, friendly name)
SESSIONS = [(1781835881068, "Session 2"), (1781836828189, "Session 3")]


def load_wav_i16(path):
    with wave.open(str(path), "rb") as w:
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2")


def envelopes(audio_i16):
    hop = SR // ENV_HZ
    freqs = np.fft.rfftfreq(N_FFT, 1.0 / SR)
    hf = (freqs >= HF_LOW) & (freqs <= HF_HIGH)
    aud = freqs >= FLOOR
    han = np.hanning(N_FFT)
    rms_env, crk_env = [], []
    for s in range(0, len(audio_i16) - hop, hop):
        frame = audio_i16[s:s + hop].astype(np.float64)
        rms_env.append(float(np.sqrt(np.mean(frame ** 2))))
        seg = audio_i16[s:s + N_FFT].astype(np.float64)
        if len(seg) < N_FFT:
            seg = np.pad(seg, (0, N_FFT - len(seg)))
        sp = np.abs(np.fft.rfft(seg * han)) ** 2
        crk_env.append(float(sp[hf].sum() / (sp[aud].sum() + 1e-9)))
    return rms_env, crk_env


bundle = []
for sid, name in SESSIONS:
    wav = RAW / f"training_{sid}.wav"
    meta = json.loads((RAW / f"training_{sid}.json").read_text())
    audio = load_wav_i16(wav)
    total_s = len(audio) / SR
    clip_start_s = max(0.0, total_s - CLIP_MIN * 60)
    clip = np.ascontiguousarray(audio[int(clip_start_s * SR):])

    tmp_wav = CLIPS / f"_tmp_{sid}.wav"
    mp3 = CLIPS / f"session_{sid}.mp3"
    with wave.open(str(tmp_wav), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(clip.tobytes())
    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-i", str(tmp_wav),
                    "-ac", "1", "-b:a", "64k", str(mp3)], check=True)
    tmp_wav.unlink()

    rms_env, crk_env = envelopes(clip)
    rmax = max(rms_env) or 1.0
    rms_n = [round(v / rmax, 3) for v in rms_env]
    crk_n = [round(min(v, 1.0), 3) for v in crk_env]

    markers = []
    for e in meta.get("confirmed", []):
        markers.append({"label": "you " + e["type"].replace("_START", "").replace("_END", "·end"),
                        "roastSec": e["elapsedMs"] / 1000, "kind": "you"})
    auto = meta.get("autoDetected", {})
    for key, lab in (("fcStartElapsedMs", "auto FC"), ("scStartElapsedMs", "auto SC")):
        if auto.get(key):
            markers.append({"label": lab, "roastSec": auto[key] / 1000, "kind": "auto"})

    bundle.append({
        "name": name, "sessionId": meta.get("sessionId"),
        "mp3": mp3.name, "totalSec": round(total_s, 1),
        "clipStartSec": round(clip_start_s, 1), "clipDurSec": round(len(clip) / SR, 1),
        "envHz": ENV_HZ, "rms": rms_n, "crk": crk_n, "markers": markers,
    })
    print(f"{name}: total {total_s:.1f}s -> clip {clip_start_s:.1f}..{total_s:.1f}s, mp3 {mp3.stat().st_size//1024} KB")

html = HTML_TEMPLATE.replace("/*DATA*/", json.dumps(bundle))
(CLIPS / "player.html").write_text(html, encoding="utf-8")
print(f"\nwrote {CLIPS / 'player.html'}")
