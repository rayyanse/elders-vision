# Elders Vision: technical notes

A voice-only Android app that acts as the eyes of a blind or low-vision person. Nobody has to find or press a
button: the app opens, greets the user, and is **live straight away** — Iris keeps watching through the camera and tells
the user what she sees as they move. She also listens all the time for **"Hey Iris"** for anything on demand.

```
mic ─► AssemblyAI streaming STT (one always-on session) ─► "Hey Iris" ─► command ─┐
camera frame ─────────────────────────────────────────────────────────────────────┤
                                                                                  ▼
speaker ◄─ AssemblyAI TTS ◄─ short, hazard-first description ◄─ Gemini vision ◄───┘
```

## Live mode (on by default)

Iris is a continuous guide, not a photo describer. She describes where you are once, then narrates *as you move*:

- **Short clips, not single photos.** The app keeps a rolling buffer of the last ~3 s of camera frames. Every look sends a
  clip of 3 frames spread over ~2 s, so Gemini can tell whether you are walking toward something, turning, or have stopped.
- **Memory of the room.** Gemini keeps running notes ("came in through the door, now behind; bed along the left wall; desk
  ahead right, ~4 steps") and gets them back on every look, so it can guide you around a space it has already seen.
- **URGENT lines** (steps, obstacles, people close) are short, preceded by a double beep, and **cut in** over whatever she was
  saying. Normal lines wait for her to finish and are dropped if stale. A hazard is warned about once, then she tells you how
  to go around it. Near-duplicate lines are suppressed.
- **Efficient:** the camera is only analysed when the picture changed (you moved) or every ~10 s. Standing still = silence.
  Looking and talking happen in parallel, so she never falls behind.
- **Guide mode** — "Hey Iris, guide me to the door": a short directive every ~1.5 s ("Turn slightly left. Door about four steps
  ahead.") until you arrive; she says "You're at the door" and ends it. "Hey Iris, stop" cancels.
- Clear commands work **without** "Hey Iris" too ("I want to go to that door", "find my keys", "stop"); open-ended questions still need the wake word.
- "Hey Iris, stop" silences her and **pauses** live mode; "Hey Iris, resume" starts it again. Any spoken command takes priority.

*Why not Gemini's Live API?* We tested it (`gemini-3.8-live`, `gemini-3.1-flash-live-preview`): it can only answer in spoken audio
(no text, so AssemblyAI could not be the voice), it did not speak up when the scene changed, it invented objects, and it also takes
the camera as ~1 JPEG frame per second. The clip + memory pipeline above is the same idea, with AssemblyAI as the voice.

## What you say

Say **"Hey Iris"** (a two-tone chime + "Yes?" means she is listening), then the command. Either in one breath
("Hey Iris, read this") or in two. After an answer she keeps listening for ~6 s, so follow-ups need no wake word.

| Say | What happens |
|---|---|
| "what's around me" / "what's in front of me" / "where am I" | Whole-scene description, hazards first |
| "read this" / "what does it say" | Reads signs, labels, medicine boxes, screens |
| "is it safe to walk" / "check my path" | 1–2 sentences about the next few steps |
| "find my keys" / "where is the door" | Says where it is and which way to turn |
| "guide me to the door" / "take me to the kitchen" / "I want to go outside" | **Guide mode**: step-by-step directions until you arrive |
| "resume" / "keep describing" / "go live" | Turns live narration back on |
| "stop" / "pause" / "stop describing" | Silences her and pauses live narration |
| "repeat" | Says the last description again |
| "help" | She lists everything she can do |
| anything else, e.g. "what colour is the sofa?" | Gemini answers about the current view |

## Medicines ("Hey Iris, help me find Panadol from these medicines")

Label-only by design. The AI model **only copies the text printed on the box**; everything Iris *says* is composed in code
(`meds.js`) from that text, so nothing that is not printed can be spoken as a fact, and she never suggests a dose.

- She tells you how to hold the box, waits until it is steady, takes three high-resolution photos and sends the two sharpest.
- She reads the exact name **including the version** (Panadol vs Panadol Extra), the strength, what the box says it is for,
  the printed dosage on request, any printed warnings and the **expiry date** (an expired box gets a clear warning).
- Anything missing is reported as missing ("this side does not say what it is for, turn it over") and never filled in.
  A strength that is cut off (`50`, `00 mg`) is never read out, and a half-hidden name is called "might be", never "found it".
- Follow-ups need no "Hey Iris": "next one", "what does it do", "how much", "any warnings", "that's the one".

## Scenery (mountains, the sea, a skyline)

"Describe the view", "I'm in the mountains, tell me everything", "paint me a picture". Iris gives a 200-250 word description a person
can imagine: any danger underfoot first, then layers near to far, sizes compared with everyday things, colour, light, weather
and mood. "More about the sky" goes deeper into one part. Distances are in metres/kilometres out there, never steps.

## Listening first

She waits for the *whole* request (longer pauses allowed, requests split by a pause are merged), stops talking when you start,
except in the middle of a safety warning, and does not start a routine line while you are mid-sentence. "Stop" never waits.

## Guiding

Before she starts walking you she gives a short route briefing (where the goal is, what is on the way, the plan). While you walk, a
remembered hazard stays active until she sees you are past it or you say "done", she never sends you toward a side you said is
blocked, and she says "I can't see well, stand still" instead of guessing when the picture is blurry or blocked.

## Run it

```bash
npm install
cp .env.example .env        # put your Gemini + AssemblyAI keys in .env
npm start                   # http://localhost:3000   (also works in a desktop browser: tap once to start)
```

### Android app (Capacitor)

The app is a thin native shell that loads the UI from the server, so the API keys never live on the phone.

1. Phone: Settings → Developer options → **Wireless debugging** on → *Pair device with pairing code*.
2. PC: `adb pair <ip:port> <code>`, then `adb connect <ip:connect-port>`.
3. `adb reverse tcp:3000 tcp:3000` (phone's `localhost:3000` → this PC; localhost counts as a secure context, so camera and mic work).
4. Build + install:
   ```bash
   npx cap sync android
   cd android && ./gradlew assembleDebug        # needs JDK 21 + Android SDK 36 (JAVA_HOME / ANDROID_HOME)
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. First install only: allow **Camera** and **Microphone** (a helper taps Allow once). After that it starts by itself.

UI/server changes need no rebuild — just reopen the app. To ship without the PC, deploy the server to an https host and
build with `APP_SERVER_URL=https://your-host npx cap sync android`.

## Things to know

- **AssemblyAI free tier = 5 new streaming sessions per minute.** The app holds one long session and has a rolling-window
  guard + backoff so reconnects never exceed that.
- The mic keeps streaming while she speaks so "Hey Iris, stop" works; anything heard while she talks (her own voice) is
  ignored unless it contains the wake phrase.
- Listening only works while the app is open and the screen is on (the screen is kept awake). Background listening would need
  a native foreground service — a good next step.
- Wake-word matching is fuzzy (Iris / Irish / Eris / Aris / "eye ris") and unit-tested: `node test/intents.test.js`.
- It's an aid, not a replacement for a cane, guide dog or a person. The prompt forbids calling a road or stairs "safe".

## Testing vs demo

```bash
npm start        # testing: cheap model everywhere (gemini-3.1-flash-lite)
npm run demo     # demo: stronger model for on-demand commands; live narration stays on the fast model
```

Why live stays on the fast model: stronger "thinking" models take 2–5 s per answer, which is too slow to narrate while you walk.
For one-shot commands the extra ~1 s buys better detail. To try another demo model, set `GEMINI_MODEL_DEMO` in `.env`.

## Testing without a phone: the virtual world (`sim/`)

A furnished 3D room (three.js) is rendered into the app as its camera, and a virtual walker follows Iris's spoken directions, so her
answers can be scored against ground truth. The real app runs unmodified in headless Edge (same user agent marker, no taps), on its
own server (port 3300) so a phone session is not disturbed.

```bash
npm run sim                    # everything (~15 min): boot, describe, read, find, hazard, quiet, guide, commands, real-audio voice
node sim/run.mjs read find     # only some scenarios
npm run sim:preview            # render the room from several viewpoints -> sim/out/*.png
node --env-file=.env sim/depth.mjs   # how well each Gemini model judges distance from one frame
```

Results go to `sim/out/` (`report.json`, logs, screenshots of what Iris "saw"). The voice scenario synthesises spoken commands with
AssemblyAI TTS, feeds them in as the microphone, and checks that AssemblyAI streaming STT + the wake word + intent parsing work end to end.

Last full run: **10 / 11 scenarios pass.** Guided walking (`guide`) does **not** yet pass reliably - see "Known limits".

## Room scan: look around first, then guide (`public/roomscan.js`, `public/roommap.js`, `/api/scan`)

Before guiding, Iris learns what is where. Say **"scan the room"**, or just ask to be guided: with no fresh map she first asks you to stand still and
**turn slowly all the way around** (one full circle, phone at chest height, tilted down a little).

1. While you turn, the phone's heading says how far you have turned; a photo is taken every ~28 degrees (only while the phone is steady, with a tick for each).
2. One careful Gemini call (`gemini-3.5-flash`, low thinking; `GEMINI_MODEL_SCAN`) lists every door, window, piece of furniture and floor hazard in every photo with a
   bounding box. An object's real direction = the photo's heading + where it sits in the frame; repeats across photos merge into one landmark
   (`{name, heading, steps}`), and two different doors stay separate (it reads signs such as "WC").
3. As you walk, step counts and heading move a "you are here" pose through the map (dead reckoning, about +/- 1 m), so the map stays usable for ~10 minutes or ~14 m of walking.
4. **The first instruction comes from the map:** "The exit door is behind you on the right, about 4 steps. Turn right, a half-way turn. Then walk straight 3 steps. The sofa is on the
   way, on your left." The app's turn coach says "Stop." when you have turned far enough. The live guide also gets the map in its prompt, so it knows which way to turn for a goal it
   cannot see. With two doors she says so ("I can see 2 doors... taking you to the nearest") and you can correct her ("the bathroom door").

Measured in the 3D room (`npm run sim -- roomscan`): all 9 objects found in the right direction after one turn, median direction error 3-4 degrees (every one within 6),
distance within about 1 step; guiding to goals behind or beside the person, 3 of 3 trips ended with the person facing the goal after the first instruction.

**Not yet verified on a real phone:** compass heading indoors can drift near metal and electronics; the camera's field of view is assumed (`CFG.fovH` = 45 degrees, calibrate it
if directions look consistently stretched); the step counter was not exercised in the simulator, so dead reckoning after you start walking is untested; and the live AI guide can
still issue its own hazard lines that compete with the map instruction. Say "stop" to cancel a scan.

## PathGuard: on-device obstacle beeps (`public/guard.js`, `public/pathsense.js`)

Research on this problem ([GenAssist](https://forge.engineering.asu.edu/wp-content/uploads/2026/03/Chatterjee_FURI.pdf), [NaviGPT](https://arxiv.org/pdf/2410.04005),
[Project Guideline](https://research.google/blog/open-sourcing-project-guideline-a-platform-for-computer-vision-accessibility-technology/)) agrees: a fast **local**
layer should handle distance and "something is close", and the language model should only name things and plan. PathGuard is that layer:

```
camera frame -> Depth Anything V2 Small (WebGPU, ~0.3 s on the Redmi) -> floor geometry (phone height + tilt) -> distance to the nearest
obstacle in the left / centre / right lane -> proximity BEEPS (faster + higher as it nears, panned to the side) + measured distances sent to Gemini
```

- **Distance comes from where the obstacle touches the floor**, which the camera's height and tilt turn into exact metres, so the depth model only has
  to say "floor / not floor". It follows a remembered floor profile over time (a big obstacle filling the picture would otherwise corrupt a single-frame fit)
  and ignores flat patches such as rugs. Alerts need 2 of the last 3 frames to agree.
- Numbers for the AI: `/api/live` receives `sensors` (lane distances) and the prompt tells Gemini to use them (approximate, +/- 0.5 m).
- Needs the phone's tilt (gyro) and assumes the phone is about 1.3 m above the floor, ~70 degree vertical field of view (constants at the top of `guard.js`).
- Loads the model from a CDN on first start (~50 MB, then cached). If anything fails it disables itself and the app works as before.

**How well it works** (measured in the 3D room, `node sim/depth-eval.mjs`, `npm run sim -- guardlayer`):
it flags about **80-90% of truly close obstacles** (median distance error 0.2-0.45 m) with a floor-contact method that gives up a few points of detection
to be robust to rugs. In the walking test it flagged 89% of the moments with something within 1.2 m ahead. **It is an aid, not a guarantee:** it misses
roughly one close obstacle in five to ten, and false beeps were measured at about 13% of clear lanes offline (and could not be measured in the walking test:
the cramped test room has almost no clear moments - judge false beeps on the real phone). Never rely on it where a miss is dangerous.

## Known limits (found by the virtual-world tests)

- **A chest-level camera cannot see low objects right in front of your feet** (a chair, a box within ~1 m). It is a physical blind spot,
  the same reason a cane is still needed. Tilting the phone down helps; Iris says "feel ahead with your hand or cane" when you stop.
- **Judging distance from one frame is imprecise** (~0.3-0.5 m error at best), so arrival is never claimed with certainty: when the fast guide
  thinks you are there, a second, more accurate model checks the distance, and Iris says "It should be right in front of you. Reach out to feel
  for it." She also says "Something low may be in front of you" if you stop.
- **Guided walking is the weakest feature**: it recovers from being stuck and refuses look-alikes ("that is an exit door, not the bathroom"),
  but it did not reliably complete trips in the cluttered test room. Do not rely on it where a mistake is dangerous.

## Config (`.env`)

| Variable | Default | Notes |
|---|---|---|
| `GEMINI_API_KEY` / `ASSEMBLYAI_API_KEY` | — | required |
| `GEMINI_MODEL` | `gemini-3.1-flash-lite` | Cheap + fast (~1 s, $0.25 / $1.50 per 1M tokens). Used for everything in testing, and **always** for live narration |
| `GEMINI_MODEL_LIVE` | `gemini-3.5-flash-lite` | The continuous live guide. Measured ~1.0 s avg / 1.4 s worst vs ~1.5 s / 2.1 s for 3.1-flash-lite, for cents more per hour |
| `GEMINI_MODEL_VERIFY` | `gemini-3.5-flash` | Arrival check in guided walking (one call per trip) |
| `GEMINI_MODEL_DEMO` | `gemini-3.5-flash` | Only with `npm run demo`: stronger model for one-shot commands (describe / read / find / ask) |
| `GEMINI_THINKING_LEVEL_DEMO` | `low` | Thinking level for the demo model (~2 s per command) |
| `TTS_VOICE` | `aai-audrey` | also `aai-peter`, `aai-gunnar` (slow, older man), `aai-carrie`, `aai-nancy`, `aai-sara`, `aai-steven` |
| `PORT` | `3000` | |

## Files

`server.js` (keys + `/api/describe`, `/api/speak`, `/api/stt-token`, `/api/log`) · `public/app.js` (voice loop, camera, TTS) ·
`public/intents.js` (wake word + commands) · `android/` (Capacitor shell) · `test/intents.test.js`
