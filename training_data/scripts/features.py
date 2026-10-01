"""
Crack-detector feature spec v2 — the SINGLE source of truth for model inputs.

`app/src/main/java/com/roastcompanion/audio/FeatureExtractor.kt` is a line-for-line
port of this file and `FeatureExtractorTest` checks it against golden vectors
produced by `make_golden.py`. If you change anything here, bump FEATURE_VERSION,
regenerate the golden file, port the change to Kotlin, and retrain.

Why v2 (v1 used librosa in training and a hand-rolled port on the phone): the two
disagreed by ~36 std-devs on MFCC c0 (int16 vs float scaling) and a few std-devs on
c1-c2 (filterbank normalisation), so the on-device model saw garbage. v2 is plain
numpy, causal (no whole-file dB clipping, no centred frames) and uses only what the
phone can compute from the current 50 ms frame and the frames before it.

Frames: 2205 samples (50 ms @ 44.1 kHz), non-overlapping, exactly as AudioRecord
delivers them to AudioAnalyzer. Per frame, FEATS_PER_FRAME = 16 values:
   0-11  MFCC c1..c12 (c0 dropped: it is absolute loudness, i.e. mic gain/placement)
   12    rel_loud  = ln((rms+1)/(ambient+1)), ambient = mean of the quietest 70% of the
                     previous AMBIENT_FRAMES frames' RMS (the frame itself excluded)
   13    crack_band = energy(2-9 kHz) / energy(>=200 Hz), Hann-windowed 4096-pt FFT
   14    flux      = mean over mel bands of max(0, logmel_t - logmel_{t-1})  (dB)
   15    crest     = ln((max|x|+1)/(rms+1))
Also per frame (NOT a model input; drives the roll detector):
   imp = impulsiveness = ln((max_b e_b + 1e-9) / (median_b e_b + 1e-9)) where e_b is the mean
         energy of 1 ms blocks (44 samples) of the 2nd difference x[i]-2x[i-1]+x[i-2]
         (i = 2..2201, within the frame, int16 units). A 1-5 ms crack pop is one or two
         blocks far above the rest; fan/drum noise is stationary at this scale.
The model input is the last CONTEXT_FRAMES frames stacked oldest->newest (80 values),
z-scored with mean/std from feature_norm.json.
"""
import numpy as np

FEATURE_VERSION = 2
SR = 44100
FRAME = 2205
N_FFT = 4096
N_MELS = 40
N_MFCC_KEEP = 12          # c1..c12
AMBIENT_FRAMES = 100      # 5 s
AMBIENT_PCT = 0.70
CONTEXT_FRAMES = 5        # 250 ms, causal
FEATS_PER_FRAME = 16
HF_LOW, HF_HIGH, AUDIBLE = 2000.0, 9000.0, 200.0

# App's SpectralGate (replicated for the offline harness; not a model input)
IMP_BLOCK = 44          # 1 ms
IMP_BLOCKS = 50         # blocks from i=2..2201
GATE_FFT = 2048
GATE_LOW_BIN = 2000 * GATE_FFT // SR      # 92
GATE_HIGH_BIN = 9000 * GATE_FFT // SR     # 417
GATE_FLOOR_BIN = max(1, 150 * GATE_FFT // SR)  # 6

N_BINS = N_FFT // 2 + 1
SPEC_FLOOR = int(AUDIBLE / SR * N_FFT)    # 18
SPEC_LOW = int(HF_LOW / SR * N_FFT)       # 185
SPEC_HIGH = int(HF_HIGH / SR * N_FFT)     # 835

HANN = 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(FRAME) / (FRAME - 1)))
GATE_HANN = 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(GATE_FFT) / (GATE_FFT - 1)))


def _hz_to_mel(hz):  # Slaney (librosa htk=False)
    hz = np.asarray(hz, dtype=np.float64)
    f_sp = 200.0 / 3.0
    mel = hz / f_sp
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    return np.where(hz >= min_log_hz, min_log_mel + np.log(np.maximum(hz, 1e-12) / min_log_hz) / logstep, mel)


def _mel_to_hz(mel):
    mel = np.asarray(mel, dtype=np.float64)
    f_sp = 200.0 / 3.0
    hz = f_sp * mel
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    return np.where(mel >= min_log_mel, min_log_hz * np.exp(logstep * (mel - min_log_mel)), hz)


def mel_filterbank():
    """Slaney-normalised triangular filterbank, identical to librosa.filters.mel
    (sr=44100, n_fft=4096, n_mels=40, fmin=0, fmax=sr/2, htk=False, norm='slaney')."""
    fftfreqs = np.linspace(0.0, SR / 2.0, N_BINS)
    mel_pts = np.linspace(_hz_to_mel(0.0), _hz_to_mel(SR / 2.0), N_MELS + 2)
    mel_f = _mel_to_hz(mel_pts)
    fdiff = np.diff(mel_f)
    ramps = mel_f[:, None] - fftfreqs[None, :]
    w = np.zeros((N_MELS, N_BINS))
    for i in range(N_MELS):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        w[i] = np.maximum(0.0, np.minimum(lower, upper))
    enorm = 2.0 / (mel_f[2:N_MELS + 2] - mel_f[:N_MELS])
    return w * enorm[:, None]


MEL_FB = mel_filterbank()
_k = np.arange(N_MELS)
DCT = np.array([np.cos(np.pi * c * (2 * _k + 1) / (2 * N_MELS)) * np.sqrt((1 if c == 0 else 2) / N_MELS)
                for c in range(N_MELS)])[1:1 + N_MFCC_KEEP]   # rows c1..c12


def frames_of(audio_i16, offset=0):
    """Non-overlapping 2205-sample frames starting at `offset` (drops the tail)."""
    a = np.asarray(audio_i16, dtype=np.int16)[offset:]
    n = len(a) // FRAME
    return a[:n * FRAME].reshape(n, FRAME)


def stream_features(frames_i16, chunk=512):
    """Per-frame arrays for one stream of consecutive frames.
    Returns dict: feat (n,16) float32, rms (n,) float32 [int16 units],
    gate_spec (n,) float32 [app SpectralGate ratio], hf_log (n,) float32 [label helper],
    imp (n,) float32 [impulsiveness, roll detector input]."""
    n = len(frames_i16)
    feat = np.zeros((n, FEATS_PER_FRAME), np.float32)
    rms = np.zeros(n, np.float64)
    gate = np.zeros(n, np.float32)
    hf_log = np.zeros(n, np.float32)
    imp = np.zeros(n, np.float32)
    logmel_all = np.zeros((n, N_MELS), np.float64)
    for s in range(0, n, chunk):
        blk = frames_i16[s:s + chunk].astype(np.float64)
        r = np.sqrt(np.mean(blk * blk, axis=1))
        rms[s:s + chunk] = r
        pk = np.max(np.abs(blk), axis=1)
        feat[s:s + chunk, 15] = np.log((pk + 1.0) / (r + 1.0))
        d2 = blk[:, 2:] - 2.0 * blk[:, 1:-1] + blk[:, :-2]
        e = (d2[:, :IMP_BLOCK * IMP_BLOCKS].reshape(len(blk), IMP_BLOCKS, IMP_BLOCK) ** 2).mean(axis=2)
        imp[s:s + chunk] = np.log((e.max(axis=1) + 1e-9) / (np.median(e, axis=1) + 1e-9))
        # model spectrum: float scale, symmetric Hann(2205), zero-pad to 4096
        P = np.abs(np.fft.rfft((blk / 32768.0) * HANN, n=N_FFT, axis=1)) ** 2
        tot = P[:, SPEC_FLOOR:].sum(axis=1) + 1e-10
        band = P[:, SPEC_LOW:SPEC_HIGH + 1].sum(axis=1)
        feat[s:s + chunk, 13] = band / tot
        hf_log[s:s + chunk] = np.log(band + 1e-12)
        lm = 10.0 * np.log10(np.maximum(P @ MEL_FB.T, 1e-10))
        logmel_all[s:s + chunk] = lm
        feat[s:s + chunk, 0:12] = lm @ DCT.T
        # app SpectralGate: int16 scale, Hann(2048) over first 2048 samples, 2048-pt FFT
        G = np.abs(np.fft.rfft(blk[:, :GATE_FFT] * GATE_HANN, axis=1)) ** 2
        gt = G[:, GATE_FLOOR_BIN:GATE_FFT // 2].sum(axis=1)
        gb = G[:, GATE_LOW_BIN:GATE_HIGH_BIN + 1].sum(axis=1)
        gate[s:s + chunk] = np.where(gt > 0, gb / np.maximum(gt, 1e-300), 0.0)
    # flux (needs previous frame)
    d = np.diff(logmel_all, axis=0)
    feat[1:, 14] = np.maximum(d, 0.0).mean(axis=1)
    # relative loudness vs causal ambient floor
    feat[:, 12] = rel_loudness(rms)
    return {"feat": feat, "rms": rms.astype(np.float32), "gate_spec": gate, "hf_log": hf_log, "imp": imp}


def rel_loudness(rms):
    out = np.zeros(len(rms), np.float32)
    k = int(AMBIENT_FRAMES * AMBIENT_PCT)   # 70 of 100 once the window is full
    for i in range(len(rms)):
        lo = max(0, i - AMBIENT_FRAMES)
        hist = rms[lo:i]
        if len(hist) == 0:
            amb = rms[i]
        else:
            cut = max(1, int(len(hist) * AMBIENT_PCT))
            amb = np.partition(hist, cut - 1)[:cut].mean() if cut < len(hist) else hist.mean()
        out[i] = np.log((rms[i] + 1.0) / (amb + 1.0))
    return out


def stack_context(feat):
    """(n,16) -> (n,80): frames t-4..t, oldest first; before the start, repeat frame 0."""
    n = len(feat)
    idx = np.arange(n)[:, None] + np.arange(-(CONTEXT_FRAMES - 1), 1)[None, :]
    idx = np.clip(idx, 0, n - 1)
    return feat[idx].reshape(n, CONTEXT_FRAMES * FEATS_PER_FRAME)
