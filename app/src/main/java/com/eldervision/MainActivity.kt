package com.eldervision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private val PERMISSIONS = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
}.toTypedArray()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ElderVisionApp()
            }
        }
    }
}

@Composable
private fun ElderVisionApp() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(PERMISSIONS) }

    if (!granted) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Iris needs the camera, microphone, and motion sensors.", color = Color.White, fontSize = 20.sp)
                Button(onClick = { launcher.launch(PERMISSIONS) }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Allow access")
                }
            }
        }
        return
    }

    AssistantScreen()
}

@Composable
private fun AssistantScreen(viewModel: AssistantViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val safety by viewModel.safety.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraFeed(
            onFrame = viewModel::onFrame,
            onVerticalFov = viewModel::setVerticalFov,
            modifier = Modifier.fillMaxSize(),
        )
        SafetyPanel(safety, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp))
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { viewModel.interrupt() },
                        onDoubleTap = { viewModel.toggleNavigation() },
                        onLongPress = { viewModel.readText() },
                    )
                },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            val mode = if (state.navigationOn) "Navigating" else "Navigation paused"
            Text("$mode · ${state.status}", color = Color(0xFF8BE28B), fontSize = 14.sp)
            if (state.missingKeys) {
                Text(
                    "Set gemini.apiKey and assemblyai.apiKey in local.properties, then rebuild.",
                    color = Color(0xFFFF8A80),
                    fontSize = 16.sp,
                )
            }
            if (state.heard.isNotEmpty()) Text("You: ${state.heard}", color = Color.White, fontSize = 16.sp)
            if (state.said.isNotEmpty()) Text(state.said, color = Color.White, fontSize = 20.sp)
        }
    }
}

@Composable
private fun SafetyPanel(safety: SafetyState, modifier: Modifier) {
    Row(modifier.background(Color(0xAA000000)).padding(8.dp)) {
        safety.preview?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
        }
        Column(Modifier.padding(start = 8.dp)) {
            val reading = safety.reading
            fun metres(value: Float?) = when {
                value == null -> "-"
                value.isInfinite() -> "clear"
                else -> "%.2f m".format(value)
            }
            val alert = reading != null && reading.calibrated && reading.nearest < Config.BEEP_START_M
            Text(
                "L ${metres(reading?.left)}  C ${metres(reading?.center)}  R ${metres(reading?.right)}",
                color = if (alert) Color(0xFFFF8A80) else Color.White,
                fontSize = 15.sp,
            )
            Text(
                if (reading?.calibrated == true) "Depth active" else "Tilt the phone: point ahead, slightly down",
                color = if (reading?.calibrated == true) Color(0xFF8BE28B) else Color(0xFFFFD54F),
                fontSize = 12.sp,
            )
            Text(
                "${safety.backend} ${reading?.inferenceMs ?: "-"} ms · tilt %.0f° · heading %.0f°".format(safety.pitchDownDeg, safety.headingDeg),
                color = Color.White,
                fontSize = 12.sp,
            )
            Text(
                "steps ${safety.steps}${if (safety.walking) " · walking" else ""} · stop voice ${if (safety.urgentReady) "ready" else "loading"}",
                color = Color.White,
                fontSize = 12.sp,
            )
        }
    }
}

private fun hasPermissions(context: Context) = PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}
