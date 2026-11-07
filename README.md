<div align="center">

<img src="docs/assets/banner.svg" width="100%" alt="Elders Vision. Meet Iris. Eyes you can just talk to.">

<br><br>

<a href="#-how-it-works"><img src="https://img.shields.io/badge/AssemblyAI-Universal%20Streaming-2545D3?style=for-the-badge" alt="AssemblyAI Universal Streaming"></a>
<a href="#-how-it-works"><img src="https://img.shields.io/badge/AssemblyAI-Text--to--Speech-2545D3?style=for-the-badge" alt="AssemblyAI Text-to-Speech"></a>
<a href="#-how-it-works"><img src="https://img.shields.io/badge/Gemini-Vision-4285F4?style=for-the-badge&logo=googlegemini&logoColor=white" alt="Gemini Vision"></a>
<br>
<img src="https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
<img src="https://img.shields.io/badge/Capacitor-119EFF?style=flat-square&logo=capacitor&logoColor=white" alt="Capacitor">
<img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
<img src="https://img.shields.io/badge/Node.js%2020.6%2B-5FA04E?style=flat-square&logo=nodedotjs&logoColor=white" alt="Node.js 20.6 or newer">
<img src="https://img.shields.io/badge/three.js-test%20world-333333?style=flat-square&logo=threedotjs&logoColor=white" alt="three.js test world">
<img src="https://img.shields.io/badge/tests-351%20checks%20passing-2EA043?style=flat-square" alt="351 checks passing">

<br><br>

**A voice-only Android app for blind, low-vision and older people.**<br>
No buttons, no menus, nothing to read. It opens, starts looking, and tells you what's around you.

<sub><img src="docs/assets/icons/b-assemblyai.svg" width="16" height="16" align="top" alt=""> Built for the AssemblyAI hackathon</sub>

<br>

[Why](#-why-we-built-it) ·
[Features](#-what-iris-can-do) ·
[How it works](#-how-it-works) ·
[Safety by design](#-safety-by-design) ·
[Testing](#-tested-in-a-virtual-world) ·
[Quick start](#-quick-start) ·
[Native app](#-native-android-app)

<br>

<img src="docs/assets/diagrams/stats.svg" width="100%" alt="Zero buttons to find, about one second per live look, 351 automated checks, nine of nine objects mapped in one turn">

<br><br>

<img src="docs/assets/showcase.png" width="100%" alt="Three phone screens of the real app: Iris describing a room, warning about a box two steps ahead, and reading a medicine box">

</div>

## <img src="docs/assets/icons/heart.svg" width="30" height="30" align="top" alt=""> Why we built it

Apps made for people who can't see well still start with a button they have to find. That's the wrong starting point, so we removed it.

Iris has no start screen. The app opens by itself, starts the camera and microphone, and is live straight away. If you close your eyes and walk around a room, she stays with you the whole way:

| Iris | What that means |
|---|---|
| <img src="docs/assets/icons/c-live.svg" width="24" height="24" align="top" alt=""> **She's live** | She describes where you are once, then narrates as you move |
| <img src="docs/assets/icons/c-warn.svg" width="24" height="24" align="top" alt=""> **She warns you first** | Hazards come with a double beep and cut in over whatever she's saying |
| <img src="docs/assets/icons/c-quiet.svg" width="24" height="24" align="top" alt=""> **She knows when to be quiet** | Standing still means silence, because nothing has changed |
| <img src="docs/assets/icons/c-memory.svg" width="24" height="24" align="top" alt=""> **She remembers** | Running notes on the room mean she can guide you around a space she has already seen |
| <img src="docs/assets/icons/c-ask.svg" width="24" height="24" align="top" alt=""> **She listens** | Say "Hey Iris" for anything else, or just talk over her |

## <img src="docs/assets/icons/zap.svg" width="30" height="30" align="top" alt=""> What Iris can do

| | Say | Iris |
|---|---|---|
| <img src="docs/assets/icons/c-describe.svg" width="24" height="24" alt=""> | *"Hey Iris, what's around me?"* | Describes the whole scene, hazards first |
| <img src="docs/assets/icons/c-read.svg" width="24" height="24" alt=""> | *"Read this"* | Reads signs, labels and screens |
| <img src="docs/assets/icons/c-pill.svg" width="24" height="24" alt=""> | *"Help me find Panadol"* | Reads medicine boxes: exact name, strength, expiry date and printed warnings |
| <img src="docs/assets/icons/c-find.svg" width="24" height="24" alt=""> | *"Find my keys"* | Says where it is and which way to turn |
| <img src="docs/assets/icons/c-safe.svg" width="24" height="24" alt=""> | *"Is it safe to walk?"* | Checks the next few steps |
| <img src="docs/assets/icons/c-scan.svg" width="24" height="24" alt=""> | *"Scan the room"* | You turn once, and she builds a map of what's where |
| <img src="docs/assets/icons/c-guide.svg" width="24" height="24" alt=""> | *"Guide me to the door"* | Briefs the route, then walks you through it |
| <img src="docs/assets/icons/c-view.svg" width="24" height="24" alt=""> | *"Paint me a picture"* | Describes a view in layers: mountains, the sea, a skyline |
| <img src="docs/assets/icons/c-ask.svg" width="24" height="24" alt=""> | *"What colour is the sofa?"* | Answers questions about what the camera sees |
| <img src="docs/assets/icons/c-stop.svg" width="24" height="24" alt=""> | *"Stop"*, *"resume"*, *"repeat"*, *"help"* | Hands-free control, and you can interrupt her at any time |

> [!TIP]
> Clear commands work without the wake word: *"I want to go to that door"*, *"find my keys"*, *"stop"*. After an answer she keeps listening for about 6 seconds, so follow-ups need no "Hey Iris".

## <img src="docs/assets/icons/wf.svg" width="30" height="30" align="top" alt=""> How it works

<img src="docs/assets/diagrams/architecture.svg" width="100%" alt="Architecture: microphone audio goes straight to AssemblyAI Universal Streaming. Commands and camera clips go through the Node server to Gemini. The answer goes through server guards to AssemblyAI Text-to-Speech and back to the speaker queue.">

### A command, start to finish

```mermaid
sequenceDiagram
    autonumber
    actor You
    participant App as Iris on the phone
    participant STT as AssemblyAI Streaming
    participant Server as Node server
    participant Gemini as Gemini vision
    participant TTS as AssemblyAI TTS

    You->>App: "Hey Iris, read this"
    App->>STT: Live microphone audio
    STT-->>App: Transcript, word by word
    App->>App: Match wake word and intent
    App->>Server: Camera frame + command
    Server->>Gemini: Image + prompt
    Gemini-->>Server: Text answer
    Server-->>App: Short spoken-style line
    App->>Server: Text to speak
    Server->>TTS: Text, voice aai-audrey
    TTS-->>Server: 24 kHz WAV
    Server-->>App: Audio
    App-->>You: Speaks the answer
```

### The live loop

This is what makes Iris a companion instead of a photo describer. It runs continuously, and it doesn't wait to be asked.

<img src="docs/assets/diagrams/live-loop.svg" width="100%" alt="One tick of live narration: motion gate, three-frame clip, Gemini live tier, server guards, announce. Memory, the active hazard and recent lines feed into the next look.">

### Three Gemini tiers

Each job uses the model that fits it. Live narration needs speed, and reading small print needs accuracy.

| Tier | Used for | Default model | Why |
|---|---|---|---|
| <img src="docs/assets/icons/c-live.svg" width="20" height="20" align="top" alt=""> **Live** | Continuous narration and guiding | `gemini-3.5-flash-lite` | About 1 s, steadier than the alternatives, no thinking step |
| <img src="docs/assets/icons/c-ask.svg" width="20" height="20" align="top" alt=""> **Fast** | Describe, find, ask | `gemini-3.1-flash-lite` | Cheap and quick for one-shot commands |
| <img src="docs/assets/icons/c-pill.svg" width="20" height="20" align="top" alt=""> **Strong** | Medicine labels, room scan, arrival check | `gemini-3.5-flash` | Accuracy matters more than a second of waiting |

## <img src="docs/assets/icons/shield.svg" width="30" height="30" align="top" alt=""> Safety by design

When someone can't check an answer with their own eyes, a confident wrong answer is dangerous. So the riskiest paths don't depend on the model getting it right.

### Medicine labels are never guessed

<img src="docs/assets/diagrams/medicine.svg" width="100%" alt="Medicine flow: Iris steadies the box, the model copies the printed text, plain code composes the sentence, and Iris speaks it.">

Panadol and Panadol Extra stay different medicines. A strength that's cut off is never read out, a half-hidden name is only ever "might be", and a missing field is reported as missing. See [`meds.js`](meds.js).

### Obstacles are measured on the device

<img src="docs/assets/diagrams/pathguard.svg" width="100%" alt="PathGuard: camera frame, Depth Anything V2 on the phone, floor geometry, three lanes, then proximity beeps and measured distances for Gemini.">

### Guiding starts with a map

<img src="docs/assets/diagrams/room-scan.svg" width="100%" alt="Room scan: turn once, Gemini lists every object, repeats merge into landmarks, and step counting tracks your position on the map.">

### And the rest

- **Guards sit between the model and the speaker.** The server fixes unsafe lines before they're spoken. If the view is blurry she says to stand still, and she never sends you toward a side marked as blocked.
- **The API keys stay off the phone.** The Android shell loads its UI from the server, which holds the keys and mints short-lived streaming tokens.
- **A warning can't be talked over.** Your voice interrupts normal speech, but a safety warning always finishes.

## <img src="docs/assets/icons/bulb.svg" width="30" height="30" align="top" alt=""> Design decisions

| Decision | Why |
|---|---|
| **Voice is the only interface** | The first draft had buttons, and the people it's for can't use them. So the app now starts by itself and runs on a wake word. |
| **Short clips, not single photos** | Three frames over two seconds show whether you're walking toward something, turning or stopped. A single photo can't. |
| **AssemblyAI for both hearing and speaking** | One voice platform handles the full loop. A single long streaming session stays open, even while she talks, so "stop" always works. |
| **No Gemini Live API on the main app** | We tested it. It answers only in audio, stayed silent when the scene changed and invented objects. Clips plus memory gave better results, with AssemblyAI as the voice. |
| **Facts before wording** | The live model fills in structured facts first (view, hazard, goal side), then writes the line. That gives the server something it can check. |
| **Local depth, cloud language** | Research on this problem agrees: a fast local layer should handle "something is close", and the language model should name things and plan. |
| **Wake-word matching is fuzzy** | Speech models hear "Iris" as "Irish" or "Eris", so the matcher accepts those and is unit-tested. |

## <img src="docs/assets/icons/flask.svg" width="30" height="30" align="top" alt=""> Tested in a virtual world

You can't ask a tester to walk into furniture, so we built a world where it's safe to fail.

<img src="docs/assets/diagrams/test-world.svg" width="100%" alt="Test rig: a three.js room and synthesised speech feed the real app, a virtual walker follows Iris's directions, and answers are scored against ground truth.">

| Suite | Covers | Result |
|---|---|---|
| `test/intents.test.js` | Wake word and command parsing | 270 checks passing |
| `test/meds.test.js` | Medicine matching and wording | 56 checks passing |
| `test/roommap.test.js` | Landmark map | 17 checks passing |
| `test/pathsense.test.js` | Floor geometry | 8 checks passing |
| `npm run sim` | Virtual-world scenarios, end to end | 10 of 11 passing in the last full run |

```bash
node test/intents.test.js   # also meds, pathsense and roommap
npm run sim                 # full virtual-world run, about 15 minutes
npm run sim -- read find    # only some scenarios
```

## <img src="docs/assets/icons/rocket.svg" width="30" height="30" align="top" alt=""> Quick start

**You'll need:** Node 20.6 or newer, a [Gemini API key](https://aistudio.google.com/apikey) and an [AssemblyAI API key](https://www.assemblyai.com/dashboard).

```bash
git clone https://github.com/shahwaizse/elders-vision.git
cd elders-vision
npm install
cp .env.example .env        # add your keys
npm start                   # http://localhost:3000
```

It also runs in a desktop browser: tap once to start. Use `npm run demo` for a stronger Gemini model on one-shot commands. Live narration stays on the fast model either way.

<details>
<summary><b>Install on an Android phone</b></summary>

<br>

You'll need JDK 21, Android SDK 36, and wireless debugging turned on.

```bash
adb pair <ip:port> <code>
adb connect <ip:connect-port>
adb reverse tcp:3000 tcp:3000
npx cap sync android
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Allow the camera and microphone on first install, which a helper can do once. After that the app starts by itself. Server and UI changes need no rebuild, so just reopen it.

To run without a PC, deploy the server to an https host and build with:

```bash
APP_SERVER_URL=https://your-host npx cap sync android
```

</details>

<details>
<summary><b>Configuration</b></summary>

<br>

Set these in `.env`.

| Variable | Default | Notes |
|---|---|---|
| `GEMINI_API_KEY` | | Required |
| `ASSEMBLYAI_API_KEY` | | Required |
| `GEMINI_MODEL` | `gemini-3.1-flash-lite` | Fast tier |
| `GEMINI_MODEL_LIVE` | `gemini-3.5-flash-lite` | Live tier, set in `.env.example` |
| `GEMINI_MODEL_VERIFY` | `gemini-3.5-flash` | Arrival check |
| `GEMINI_MODEL_DEMO` | `gemini-3.5-flash` | One-shot commands under `npm run demo` |
| `TTS_VOICE` | `aai-audrey` | Also `aai-peter`, `aai-gunnar`, `aai-carrie`, `aai-nancy`, `aai-sara`, `aai-steven` |
| `PORT` | `3000` | |

The AssemblyAI free tier allows 5 new streaming sessions per minute. The app holds one long session and uses a rolling-window guard with backoff, so reconnects stay under that limit.

</details>

## <img src="docs/assets/icons/phone.svg" width="30" height="30" align="top" alt=""> Native Android app

[`native-android/`](native-android) is a separate, fully native version that runs with no server.

| | Capacitor app (root) | Native app (`native-android/`) |
|---|---|---|
| **UI** | Web UI in a thin Android shell | Kotlin and Jetpack Compose |
| **Needs a server** | Yes | No |
| **Vision** | Gemini clips plus memory | Gemini Live API |
| **Voice** | AssemblyAI STT and TTS | AssemblyAI STT and TTS, reading Gemini Live's transcript aloud |
| **On-device models** | Depth Anything V2 on WebGPU | MiDaS depth and EfficientDet detection on LiteRT and MediaPipe |
| **API keys** | Stay on the server | Compiled into the APK |

The native app also has a step counter, recorded routes and voice-triggered screen recording. Room scan, path guidance, spoken stop warnings and proximity beeps are built but turned off by default, with the flags at the top of `Config.kt`.

Create `native-android/local.properties`, then run `./gradlew assembleDebug` from that folder:

```properties
sdk.dir=/path/to/android/sdk
gemini.apiKey=your-gemini-key
assemblyai.apiKey=your-assemblyai-key
```

> [!WARNING]
> This version compiles your keys into the APK. Use it for development only, and don't distribute a build.

## <img src="docs/assets/icons/layers.svg" width="30" height="30" align="top" alt=""> Tech stack

| | Technology | Role |
|---|---|---|
| <img src="docs/assets/icons/b-assemblyai.svg" width="24" height="24" alt=""> | **AssemblyAI** | Universal Streaming for hearing, Text-to-Speech for Iris's voice |
| <img src="docs/assets/icons/b-gemini.svg" width="24" height="24" alt=""> | **Gemini** | Vision, reasoning and room memory |
| <img src="docs/assets/icons/b-node.svg" width="24" height="24" alt=""> | **Node.js + Express** | Keys, prompts, safety guards |
| <img src="docs/assets/icons/b-capacitor.svg" width="24" height="24" alt=""> | **Capacitor** | Android shell |
| <img src="docs/assets/icons/b-kotlin.svg" width="24" height="24" alt=""> | **Kotlin + Jetpack Compose** | Native app |
| <img src="docs/assets/icons/b-mediapipe.svg" width="24" height="24" alt=""> | **Depth Anything, MiDaS, MediaPipe** | On-device depth and object detection |
| <img src="docs/assets/icons/b-three.svg" width="24" height="24" alt=""> | **three.js + Playwright** | Virtual test world |

## <img src="docs/assets/icons/tree.svg" width="30" height="30" align="top" alt=""> Project layout

```text
elders-vision/
├── server.js             API keys, Gemini prompts, safety guards, TTS and STT tokens
├── meds.js               Medicine logic: what Iris may say from a label
├── public/
│   ├── app.js            Voice loop, camera, live mode, speaker queue
│   ├── intents.js        Wake word and command parsing
│   ├── guard.js          PathGuard on-device depth and beeps
│   ├── pathsense.js      Floor geometry and lane distances
│   ├── roomscan.js       Room scan capture
│   └── roommap.js        Landmark map and dead reckoning
├── android/              Capacitor Android shell
├── native-android/       Standalone Kotlin app
├── sim/                  3D test world and scenarios
├── test/                 Unit tests
└── docs/                 Technical notes, diagrams and assets
```

## <img src="docs/assets/icons/book.svg" width="30" height="30" align="top" alt=""> Learn more

- [Technical notes](docs/TECHNICAL.md): full behaviour, measurements and known limits
- [Architecture page](docs/architecture.html): the original system diagrams

## <img src="docs/assets/icons/alert.svg" width="30" height="30" align="top" alt=""> Safety notice

> [!CAUTION]
> Iris is an aid, not a replacement for a cane, a guide dog or another person. On-device depth misses some obstacles, a chest-height camera can't see low objects right at your feet, and guided walking is still experimental. Don't rely on it where a mistake could hurt someone.

<div align="center">
<br>

**See it. Hear it. Live it.**

</div>
