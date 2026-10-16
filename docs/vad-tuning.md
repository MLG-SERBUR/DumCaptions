# VAD Soft-Speech Tuning — Handoff

## Goal

Fix VAD suppressing soft voices in Discord voice-channel captions without opening the
floodgate to noise. Every false PASS costs a Groq call plus shared rate-limiter pressure
(Discord 429 backoff delays captions for everyone), so recall gains must be validated
against precision cost. Identity mapping (User A, B, ...) lives in
`docs/user-mapping.local.md`, which is gitignored and stays on this machine.

## Background

- `amp=` in logs is the **peak** absolute PCM sample across the whole buffered clip
  (typically 6–10s), computed in `CaptionsManager.preparePacketBatch`. One spike sets it.
  Clean soft voices score low peaks; noisy mics with one spike score high.
- TEN-VAD threshold was lowered twice for this same complaint (0.5 → 0.45 → 0.40;
  see git history `cffa954`, `c43713f`). Earlier per-user learned thresholds
  (`8e3f861`, `7e00737`) were ripped out as too complex (`28e05ad`). Do not repeat
  either approach blindly: lowering the global threshold further opens the ratio gate
  (2 frames + 5% + 1 high-confidence frame) to noise for all users.
- Verified from 24h of logs: low peak correlates with low VAD scores. Median
  `max_prob` for User A: 0.39 at `amp<1k`, 0.67 at `1k–8k`, 0.87 at `8k+`.
  The adaptive rescue path demands *higher* probs (0.55/0.50) than the base
  threshold (0.40), which is exactly backwards for soft speech — that is the prime
  suspect, not the global threshold.
- Current gate layout: ratio path has **no** amplitude check (short bursts pass);
  long sparse buffers fall into adaptive rescue, which requires `amp>=8000`
  (`CaptionsConfig.ADAPTIVE_MIN_AMPLITUDE`). User A passes with median amp ~9700,
  living right at the gate; Users B/C typically peak at 15–30k.

## Instrumentation (deployed, data collecting)

Two mechanisms, both log-only, no audio persisted:

1. **Shadow energy metrics** on every `VAD PASS/REJECT` line, computed per 20ms
   frame in `VadAnalyzer` (decision logic unchanged):
   - `rms=mean/std/max/speech`: whole-buffer RMS mean, std (burstiness), max, and
     mean RMS over speech-flagged frames only (`speech`). Whole-buffer mean dilutes
     in long silence-padded buffers; `speech` is the direct voice-level candidate
     to replace peak.
   - `crest=peak/rmsMean`.
   - `run=longest/count`: longest speech run plus number of separate speech bursts
     (`count`). Count separates a single clustered utterance from spread speech;
     prefer it over `span` (span punishes clustered utterances in long buffers).
   - `minp`: weakest speech-frame probability — the floor for setting avg gates.
   Rationale: peak overvalues single spikes and undervalues sustained soft speech;
   RMS spread should separate bursty speech from flat sustained noise. Thresholds
   unknown — that is what the data is for.
2. **Near-miss probes**: rejected buffers matching
   `total>=45 frames, amp>=500, max_prob>=0.45 or hi_conf>=1 or speech>=2`
   are sent to Groq anyway (capped at 10/user/hour via `VAD_PROBE_*` in
   `CaptionsConfig`). True silence is excluded. Probe captions display normally.
   `VAD PROBE SKIP <name> (hourly cap)` lines reveal whether the sample was
   budget-limited (biased toward early-hour rejects) or complete — check their
   frequency before trusting HIT/MISS ratios.

Log lines to grep (service `dum-captions`, user unit):

- `VAD PROBE <name> [PROBE#n]: <full VAD debug>` — probe sent.
- `PROBE RESULT <name> [PROBE#n] HIT '<text>' VAD: ...` — Groq returned speech:
  a caption that would previously have been lost.
- `PROBE RESULT <name> [PROBE#n] MISS (Groq seg=N ns=K comp=J) VAD: ...` — Groq
  agreed it was noise. The breakdown matters: `seg=0` means Groq heard nothing at
  all (true silence); `ns`/`comp` counts show returned segments killed by the
  no-speech or compression filters (speech-like but rejected). Tune accordingly.
- Groq per-segment lines already carry the `[PROBE#n]` tag, linking VAD metrics
  to `no_speech_prob` / `avg_logprob` ground truth.

Example (aliases): `VAD REJECT User A: **13/444 (3%)**, ... max_prob=0.50,
avg_prob=0.45, hi_conf=0, span=275 (62%), run=3, amp=751, rms=..., crest=...`
is a suspected soft-speech miss killed by the amp gate with zero high-confidence
frames. Contrast with true-silence rejects at `amp<300, max_prob<0.4, hi_conf=0`,
which must keep being rejected (about half of User A's rejects look like this).

## Analysis procedure for the next session

1. Pull logs: `journalctl --user -u dum-captions --since "48 hours ago"`.
2. Compute overall and per-user HIT rate from `PROBE RESULT` lines.
3. Bucket HITs vs MISSes by `amp`, `rmsMean`, `rmsSpeechMean`, `rmsStd`,
   `crest`, `hi_conf`, `max_prob`, `minp`, `span`, `run longest/count`. The
   separating boundary between the buckets is the new rescue rule. Pay special
   attention to:
   - whether `crest`, `rmsSpeechMean`, or `rmsStd` separates HITs from MISSes
     better than peak;
   - the `hi_conf` distribution of HITs (earlier data suggests many HITs carry
     only 1–2 high-confidence frames, so a `hi_conf>=5` gate would be too strict);
   - `minp` as the floor for any lowered avg-probability gate;
   - the `span` distribution (clustered utterances in long buffers have narrow
     span, e.g. 4%, so a wide-span requirement would reject real speech —
     prefer `run` count over `span`).
4. Cost check: `API empty` rate and Discord `429` frequency must not climb
   versus pre-probe baseline. If they do, the probe cap or eligibility band is
   too wide.
5. Derive the final rule as a change confined to `AdaptiveVadDecisionStrategy`
   (keep ratio path and `VAD_THRESHOLD=0.40` untouched), then set
   `VAD_PROBE_ENABLED=false`.

## Cases the final rule must satisfy

| # | Case | Typical signature | Required verdict |
|---|------|-------------------|------------------|
| 1 | Soft sustained speech (User A) | low peak 1–6k, low RMS, `max 0.6–0.75`, some `hi_conf`, wide-ish run | PASS |
| 2 | Loud spike over noise (yell, reaction) | high peak, short run, strong VAD burst | PASS (peak path, unchanged) |
| 3 | Sustained noise (fan, hum, typing, breath) | low-mid peak/RMS, `max<0.5`, `hi_conf 0` | REJECT |
| 4 | True silence / idle mic | `amp<300–500`, `max<0.45`, `hi_conf 0` | REJECT (hard floor, probe-excluded) |
| 5 | Loud sustained speech (Users B/C) | high peak + RMS + strong VAD | PASS (ratio path, unchanged) |
| 6 | Transient click/pop | single high-peak frame, 1 speech frame, no span/run | REJECT |
| 7 | Short reactions in long buffers ("yes", "no", laugh) | 1–2 speech frames in 100+ total, `<5%` | Undecided — current plan has a gap here; check probe data, then either accept the gap or add a narrow tier |
| 8 | Corrupt/lossy buffers (`dropped`, `gaps`, `reordered`) | partial frames | No special handling; metrics degrade gracefully |
| 9 | Overlap-heavy buffers (`overlap=1500ms`) | retained tail audio | Note Groq cost of re-transcribing tails; dedup is text-level only |
| 10 | Background speech-like noise (TV, second speaker, open mic) | bursty energy + VAD false-positive | Accept as PASS if reached — no energy metric separates it; only per-user gating would, out of scope |

## Constraints carried forward

- No audio mutation, no volume boosting, no gain normalization of any signal path.
  Scoring-only changes.
- No return to per-user learned thresholds (removed once for complexity).
- Keep peak metric; add sustained-energy signals alongside, never instead.
- Probe budget exists to bound Groq spend; any permanent recall increase must be
  re-checked against `API empty` and 429 rates.
