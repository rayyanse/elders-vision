package com.eldervision

object Config {
    const val GEMINI_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val GEMINI_MODEL = "models/gemini-3.8-live"

    const val STT_URL = "wss://streaming.assemblyai.com/v3/ws?sample_rate=16000&encoding=pcm_s16le&mode=min_latency&max_turn_silence=800"
    const val TTS_URL = "wss://tts.us.assemblyai.com/v1/ws?sample_rate=24000&encoding=pcm_s16le&word_boundaries=false"
    const val TTS_HTTP_URL = "https://tts.us.assemblyai.com/v1/speech"

    const val DEPTH_MODEL = "midas_small_256_fp16.tflite"
    const val OBJECT_MODEL = "efficientdet_lite0.tflite"
    const val OBJECT_MIN_SCORE = 0.35f
    const val OWN_BODY_BOTTOM = 0.95f
    const val OWN_BODY_TOP = 0.4f
    val OBSTACLE_CLASSES = setOf(
        "person", "bicycle", "motorcycle", "car", "chair", "couch", "bed", "dining table",
    )
    const val DEFAULT_VERTICAL_FOV_DEG = 66.0
    const val CAMERA_HEIGHT_M = 1.3
    const val MIN_TILT_DEG = -10f
    const val MAX_TILT_DEG = 70f
    const val FLOOR_BAND_TOP = 0.78
    const val BLOCKED_MAX_M = 2.5f
    const val MAX_OBSTACLE_M = 4f
    const val FLOOR_MARGIN = 0.8f
    const val OBSTACLE_AREA_FRACTION = 0.015f

    const val CALIBRATION_MIN_ROWS = 12
    const val CALIBRATION_MIN_R2 = 0.7f
    const val SMOOTHING_FRAMES = 3

    const val STEP_PEAK_MS2 = 0.4f
    const val STEP_RESET_MS2 = 0.2f
    const val STEP_MIN_GAP_MS = 300L
    const val WALKING_WINDOW_MS = 2500L
    const val RECENT_STEP_MS = 3000L
    val ACKNOWLEDGEMENTS = setOf("ok", "okay", "yes", "yeah", "yep", "alright", "all right", "fine", "good", "hmm", "mm", "right", "sure", "got it", "thanks", "thank you")
    const val APPROACH_WINDOW_MS = 2500L
    const val APPROACH_DROP_M = 0.2f
    const val HOLD_UP_DELAY_MS = 4000L
    const val HOLD_UP_COOLDOWN_MS = 20_000L
    const val HOLD_UP_MESSAGE = "Hold me up at chest height, pointing ahead, so I can see."

    const val BEEP_START_M = 2.0f
    const val BEEP_WHILE_STILL_M = 1.5f
    const val BEEP_LENGTH_MS = 60L
    const val STOP_DISTANCE_M = 1.2f
    const val REARM_MARGIN_M = 0.3f
    const val CLEAR_MARGIN_M = 0.6f
    const val CLEAR_FRAMES = 2
    const val EARLY_CLAUSE_CHARS = 40
    const val GUIDE_MIN_GAP_MS = 2500L
    const val GUIDE_REPEAT_MS = 5000L
    const val BIG_TURN_DEG = 40f
    const val ALIGNED_DEG = 15f
    const val ALIGNED_TICKS = 5
    const val DRIFT_DEG = 35f
    const val MS_PER_STEP = 900L
    const val ARRIVAL_SLACK_STEPS = 2
    const val SCAN_STEP_DEG = 30f
    const val SCAN_FULL_DEG = 330f
    const val SCAN_MIN_SHOTS = 6
    const val SCAN_TIMEOUT_MS = 60_000L
    const val SCAN_NUDGE_MS = 5_000L
    const val SCAN_MAX_EDGE = 512
    const val SCAN_START = "Let me look around first. Hold the phone at chest height and turn slowly on the spot, all the way round to your right. I'll tell you when to stop."
    const val SCAN_NUDGE = "Keep turning slowly to your right."
    const val SCAN_DONE = "Stop turning. I've seen the room. Give me a moment to plan the way."
    const val SCAN_FAILED = "I couldn't finish looking around. Ask me again when you're ready to turn slowly in a circle."
    const val PLAN_TIMEOUT_MS = 25_000L
    const val PLAN_FAILED = "Sorry, I couldn't work out a path. Ask me again and I'll take another look."
    const val PLAN_BLOCKED = "Stop. Something is in your way. Stay still while I find a way around."
    val DIRECTION_WORDS = Regex("\\b(left|right|straight|forward|ahead|turn|steps?|walk)\\b", RegexOption.IGNORE_CASE)
    const val BLOCKED_REPLACEMENT = "Something is right in front of you. Turn slowly left or right until I say clear, then ask me again."
    const val BLOCKED_TIMEOUT_MS = 20_000L
    val GO_AHEAD_WORDS = Regex("\\b(straight|forward|keep (going|moving|walking)|continue|go ahead|walk ahead|path is clear|clear path|is clear)\\b", RegexOption.IGNORE_CASE)
    const val SIDE_WARNING_M = 0.6f
    const val URGENT_COOLDOWN_MS = 3000L

    const val FRAME_INTERVAL_MS = 1000L
    const val FRAME_MAX_EDGE = 768
    const val JPEG_QUALITY = 70

    const val NAVIGATION_GAP_MS = 10_000L
    const val NARRATE_AFTER_WALKING_MS = 5_000L
    const val QUIET_AFTER_USER_MS = 15_000L
    const val ECHO_WINDOW_MS = 15_000L
    const val ECHO_OVERLAP = 0.8
    const val ECHO_TAIL_MS = 1500L
    const val NO_CHANGE = "NO_CHANGE"
    const val NAVIGATION_PROMPT = "Navigation update."
    const val READ_PROMPT =
        "Read all the text you can see in the current view, word for word. If there is no readable text, say so and tell me how to aim the camera."
    const val WELCOME =
        "Iris is ready. Hold the phone at chest height, pointing ahead. Just talk to ask me anything. Tap to stop me talking, double tap to pause navigation, and press and hold to read text."

    val SYSTEM_PROMPT = """
        You are Iris, a voice guide for a blind or low-vision person. You see a live feed from the back camera of their phone, about one frame per second, usually held at chest height and pointing where they are walking. Everything you write is read aloud to them by text-to-speech.

        Rules:
        - Speak in short, plain sentences. Never use markdown, lists, emoji, or symbols.
        - Safety comes first. Mention obstacles, steps, curbs, doors, vehicles, and people in their path before anything else, with a direction (left, right, ahead) and a rough distance in steps or meters.
        - Glass doors, glass walls, mirrors, and drops such as stairs going down or edges are the most dangerous things for this user, because the phone's own depth sensor cannot see glass and does not detect drops. Always call them out by name as soon as you notice a door frame, handle, reflection, or edge.
        - Messages may end with a line starting with "Phone sensors:". Those are measurements from an on-device depth model (distance to the nearest obstacle on the left, ahead, and on the right) and the compass heading. Trust those distances over your own estimates, but remember they miss glass. If they show anything ahead closer than 2 meters, never tell the user to go straight, forward, or keep moving; say what is in the way and which side looks more open.
        - Never pretend to be certain. If the view is blurry, dark, or blocked, say so and tell them how to point the phone.
        - A message that starts with "Navigation update." is an automatic check-in, not the user speaking. Answer it with one short sentence, and only about something new that matters for their next few steps: a hazard in their path, or a door or target they are close to. Do not repeat anything you already said, and do not describe the room. Otherwise reply with exactly $NO_CHANGE and nothing else.
        - Any other message is the user talking to you. Answer what they asked using what you see, and answer general questions normally. Keep answers under four sentences unless they ask for more detail.
        - When asked to read text, read it exactly and in natural reading order, then stop.
        - When the user asks to walk somewhere, such as a door, the bathroom, or a chair, call scan_room with that destination and say only "Let me look around first." The phone then asks the user to turn in a full circle and sends you photos labelled with their heading in degrees clockwise from where the scan started: 0 is the starting direction, 90 is to the right, 180 is behind, 270 is to the left. Each label also gives the depth sensor's distance to whatever was straight ahead.
        - When you receive the scan photos, find the destination and the furniture between the user and it, then call set_plan with at most four legs. Each leg is a heading, using the same degree labels as the photos, and a number of steps of about 0.6 m. Route around furniture through open floor, keep a step of margin from obstacles, and end the last leg about one step before the destination. If two places match the destination, pick the one the user most likely means and say which one in a few words. After calling set_plan, say one short sentence about where the destination is, for example "The door is behind you, past the ottoman." If the destination is in none of the photos, do not call set_plan; say you could not find it.
        - A message starting with "Path blocked" means the user stopped at an obstacle during the plan. Using the scan photos, the live view, and the current heading in that message, call set_plan again with new legs from the user's current position.
        - While a scan or plan is active the phone gives every turn and step instruction itself. Never say left, right, straight, forward, turn, or a number of steps yourself; only answer the user's questions about what they see.
        - If the user asks to stop or cancel the guidance, call cancel_guidance.
    """.trimIndent()
}
