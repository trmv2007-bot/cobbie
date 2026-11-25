# Cobbie 0.53.0 — his chat lines, out loud

Artifact: `cobbie-0.53.0.jar`.

The sentences he says can now be spoken. Off by default, on both his side and yours: `voice_allowed`
is `false` until you turn it on, because a companion that talks out loud uninvited during a call is
a nuisance.

## Two engines were measured on this machine, and the number chose one

| engine | twelve-word line: synthesis → first sample | how it runs |
|---|---|---|
| Kokoro (`kokoro_onnx` 0.6.1, model already on disk) | **8.5–9.3 s** for 3.35 s of audio — 2.5–2.8× realtime | CPU only, no GPU provider |
| Windows SAPI (`System.Speech`) | **194 ms** for 4.92 s of audio — 25× realtime | already installed |

Kokoro sounds better and is not usable for this: a warning spoken nine seconds after the creeper was
behind you is a sentence about a situation that no longer exists, and it teaches him to be ignored.
Nothing was downloaded to reach this decision — the Kokoro model files were already in
`~/.cache/hyperframes/tts`.

**Kokoro is still on this machine and still a config value away.** It fits behind the same
`VoiceEngine` interface; putting it on the GPU is a `onnxruntime-gpu` install, and it should land in
the 0.3–0.6 s range on the 3050. That install is Rishi's call, not this release's.

## The seam

- **`VoiceEngine`** — text in, WAV out. `SapiVoiceEngine` is the implementation; `voice_engine: "none"`
  removes it, and a better engine later is one new class.
- **The listener speaks.** The server sends the sentence to players within earshot and no audio
  crosses the wire, for two reasons that decide it. A dedicated server has no voices at all, so
  synthesis there could only ever be silent or broken. And whether he is heard is the listener's
  choice: a player with voice off must not hear him, which a server-side speaker cannot offer. This
  is also the shape multiplayer will need, where several listeners each have their own machine and
  their own answer to that question.
- **`voice_allowed`** (default `false`), **`voice_engine`** (`sapi`), **`voice_name`**
  (`Microsoft Hazel Desktop`), **`voice_max_ms`** (1500).
- **Never blocks.** `SpeechService.offer` stores the line and returns; synthesis runs on one daemon
  thread. A burst collapses to the newest line rather than being read out in full.
- **Off costs nothing.** With `voice_allowed` false, `offer` returns before it consults the engine or
  starts anything; a disabled feature that still spends a thread per line is the kind of cost nobody
  notices until a hundred lines in, so it is asserted rather than assumed.
- **Never stamps.** A line that takes longer than `voice_max_ms` is dropped instead of played late.
  The cap is a feature rather than a limitation: it is the same principle as choosing 194 ms over
  8.5 s, applied per line — a sentence about a moment that has passed is worse than silence, because
  it teaches him to be ignored.
- **One log line.** A missing engine, a failed synthesis or a slow one degrades to text with a single
  `warn` and one counter; the reply is already on screen and is never delayed or lost.

## The voice, and how it was chosen

`Microsoft Hazel Desktop` — en-GB, female. Measured on the same line: Hazel moves across **113 Hz**
of pitch (143–256) at the slowest pace of the three, Zira across 94 Hz and David across 52 Hz. Pitch
movement and pace are what separate a voice that sounds like a person from one that sounds like a
GPS, and Hazel is the widest of the three by both. Straight answer on the method: this is measured
pitch range and rate, not a pair of ears — I cannot hear the audio, so the claim is about the
numbers. All three are one config value apart.

## Verification

**Unit:** the voice seam is tested on its failures, because those are the ones that would cost him a
reply — a slow engine (dropped, never played), a broken engine, an absent engine, voice off (the
engine is never called at all), `offer` returning in under 100 ms while the engine is busy, and a
burst collapsing to the newest line. Two Windows-only tests run the **real** SAPI engine and assert a
RIFF/WAVE header with audio behind it, including that an unrecognised voice name falls back to the
system default instead of going silent.

**Not verified:** that it is audible in game. A gametest has no loopback device to assert against, so
"sound came out of the speakers at the right moment" is your check, not the machine's: turn
`voice_allowed` on, ask him something, and listen.

**Still not verified from 0.47.0:** minecarts, unchanged.
