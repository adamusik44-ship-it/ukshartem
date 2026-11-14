package app.henachti.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.henachti.R
import app.henachti.data.Prefs
import app.henachti.domain.DayPolicy
import app.henachti.service.BlockerService
import app.henachti.verify.WindingDetector
import app.henachti.widget.StatusWidget
import kotlinx.coroutines.delay
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val CamDark = Color(0xFF181512)
private val CamSheet = Color(0xFF211E1B)
private val CamPill = Color(0xFF34302C)
private val CamTrack = Color(0xFF4A4540)
private val CamGrey = Color(0xFFB9B2A8)
// the app's gold for progress and markers
private val CamBlue = Color(0xFFC9A75C)

private val TIPS = listOf(
    "קרב את הטלפון עד שהאמה ממלאת את המסגרת.",
    "סובב את היד לאור. צל חזק מסתיר כריכות.",
    "הרם את השרוול מעל המרפק.",
    "החזק את היד יציבה שנייה או שתיים.",
    "האמה לרוחב המסגרת, והרצועות חוצות אותה.",
)

/**
 * Live camera only. The preview is analysed a few times a second; the forearm has to sit
 * inside the guide with the strap windings crossing it. There is no gallery, no capture
 * button and no network: when enough windings are seen on several consecutive frames,
 * the day is verified.
 */
class VerifyActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var policy: DayPolicy
    private val lensFacing = mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    private val liveBands = mutableIntStateOf(0)
    private val phase = mutableStateOf<Phase>(Phase.Scanning)
    private val recent = ArrayDeque<Boolean>()
    private val recentBands = ArrayDeque<Int>()
    private val torchOn = mutableStateOf(false)
    private val cameraRef = mutableStateOf<Camera?>(null)
    private var bestShown = 0
    private var lastAnalysedAt = 0L
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private sealed class Phase {
        data object Scanning : Phase()
        data class Verified(val note: String) : Phase()
        data class Failed(val message: String) : Phase()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // a dark full-screen camera: draw under the bars, with light icons on them
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        prefs = Prefs.get(this)
        policy = DayPolicy(this)

        setContent {
            HenachtiTheme {
                var hasPermission by remember { mutableStateOf(cameraGranted()) }
                val permission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> hasPermission = granted }

                LaunchedEffect(Unit) {
                    if (!hasPermission) permission.launch(Manifest.permission.CAMERA)
                }

                VerifyScreen(
                    hasPermission = hasPermission,
                    onClose = { finish() },
                    onRequestPermission = { permission.launch(Manifest.permission.CAMERA) },
                )
            }
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun cameraGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Runs on the analysis thread, a few times per second. */
    private fun onFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysedAt < 180L || phase.value !is Phase.Scanning) return
            lastAnalysedAt = now
            val raw = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val upright = if (rotation != 0) {
                Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, false
                )
            } else raw
            val reading = WindingDetector.read(upright)
            runOnUiThread { handleReading(reading.bands) }
        } catch (e: Exception) {
            // skip this frame
        } finally {
            image.close()
        }
    }

    private fun handleReading(bands: Int) {
        if (phase.value !is Phase.Scanning) return
        // what the screen shows: the best of the last three frames, so the dots do not flicker
        recentBands.addLast(bands)
        while (recentBands.size > 3) recentBands.removeFirst()
        val shown = (recentBands.maxOrNull() ?: 0).coerceAtMost(WindingDetector.REQUIRED)
        liveBands.intValue = shown
        // one soft tick for every winding that is seen for the first time in this attempt
        if (shown > bestShown) {
            bestShown = shown
            window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        recent.addLast(bands >= WindingDetector.REQUIRED)
        while (recent.size > 6) recent.removeFirst()
        if (recent.count { it } >= 4) {
            prefs.markVerified(policy.currentDay())
            prefs.verifiedCount = prefs.verifiedCount + 1
            phase.value = Phase.Verified("זוהו $bands כריכות על האמה.")
            window.decorView.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.LONG_PRESS
            )
            BlockerService.instance?.updateNotification()
            StatusWidget.updateAll(this)
        }
    }

    /**
     * Full-screen camera, dark: a close button and a torch on top with one line of instruction,
     * a rounded frame with seven numbered markers that fill as windings are seen, and a bottom
     * sheet with a seven-step bar, the count, one hint and the two actions.
     */
    @Composable
    private fun VerifyScreen(
        hasPermission: Boolean,
        onClose: () -> Unit,
        onRequestPermission: () -> Unit,
    ) {
        var facing by lensFacing
        var torch by torchOn
        val camera = cameraRef.value
        val bands = liveBands.intValue
        val current = phase.value
        var tip by remember { mutableIntStateOf(-1) }

        // after 10 seconds, one tip at a time, changing every few seconds
        LaunchedEffect(Unit) {
            delay(10_000)
            var i = 0
            while (true) {
                tip = i % TIPS.size
                i++
                delay(4_500)
            }
        }
        LaunchedEffect(torch, camera) {
            camera?.let { if (it.cameraInfo.hasFlashUnit()) runCatching { it.cameraControl.enableTorch(torch) } }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(CamDark)
        ) {
            if (hasPermission) {
                CameraPreview(facing)
            } else {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("צריך גישה למצלמה כדי לאמת.", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRequestPermission) {
                        Text("אשר גישה", color = CamBlue, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // the frame the detector looks at, with one numbered marker per winding
            if (hasPermission && current is Phase.Scanning) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .fillMaxHeight(0.30f)
                        .border(2.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(28.dp))
                ) {
                    if (bands == 0) {
                        ArmGuide(
                            Modifier
                                .fillMaxSize()
                                .padding(18.dp)
                        )
                    }
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 26.dp, vertical = 16.dp)
                            .clearAndSetSemantics { contentDescription = "זוהו $bands מתוך ${WindingDetector.REQUIRED} כריכות" },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        repeat(WindingDetector.REQUIRED) { i -> Marker(i + 1, i < bands) }
                    }
                }
            }

            // top: close, torch, the instruction
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(CamDark.copy(alpha = 0.78f))
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, bottom = 16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "סגירה", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    val torchUsable = facing == CameraSelector.LENS_FACING_BACK && camera?.cameraInfo?.hasFlashUnit() == true
                    IconButton(onClick = { torch = !torch }, enabled = torchUsable) {
                        Icon(
                            painterResource(R.drawable.ic_flashlight),
                            contentDescription = if (torch) "כיבוי פנס" else "הדלקת פנס",
                            tint = when {
                                !torchUsable -> Color.White.copy(alpha = 0.35f)
                                torch -> Palette.Sun
                                else -> Color.White
                            },
                        )
                    }
                }
                Text(
                    "הכנס את האמה עם הכריכות למסגרת.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Text(
                    "מהמרפק עד כף היד. אור טוב, בלי שרוול.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CamGrey,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            when (current) {
                is Phase.Verified -> Overlay(title = "הנחת. יום טוב.", body = current.note, unlocked = true)
                is Phase.Failed -> Overlay(title = "משהו השתבש.", body = current.message, unlocked = false)
                Phase.Scanning -> Unit
            }

            // bottom sheet
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(CamSheet)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp)
            ) {
                when (current) {
                    Phase.Scanning -> {
                        Row(Modifier.fillMaxWidth()) {
                            repeat(WindingDetector.REQUIRED) { i ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .padding(horizontal = 3.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(if (i < bands) CamBlue else CamTrack)
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            when {
                                bands >= WindingDetector.REQUIRED -> "כל ${WindingDetector.REQUIRED} הכריכות. רגע אחד"
                                bands > 0 -> "רואה $bands מתוך ${WindingDetector.REQUIRED} כריכות"
                                else -> "לא רואה כריכות עדיין"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (tip >= 0 && bands < WindingDetector.REQUIRED) "${TIPS[tip]} האימות קורה לבד." else "האימות קורה לבד, בלי לחיצה.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CamGrey,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(CamPill)
                                    .clickable(role = Role.Button) {
                                        torch = false
                                        facing = if (facing == CameraSelector.LENS_FACING_BACK)
                                            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                                    }
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(painterResource(R.drawable.ic_flip_camera), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("החלף מצלמה", style = MaterialTheme.typography.labelLarge, color = Color.White)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onClose) {
                                Text("חזרה", style = MaterialTheme.typography.labelLarge, color = CamGrey)
                            }
                        }
                    }
                    is Phase.Verified -> GoldButton("המשך", onClose, Modifier.fillMaxWidth(), background = Palette.Cream, content = Palette.Teal)
                    is Phase.Failed -> GoldButton("חזרה", onClose, Modifier.fillMaxWidth(), background = Palette.Cream, content = Palette.Ink)
                }
            }
        }
    }

    /** A winding marker on the frame: an empty ring, or a blue disc with its number once seen. */
    @Composable
    private fun Marker(index: Int, on: Boolean) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (on) CamBlue else Color.Transparent)
                .border(2.dp, if (on) CamBlue else Color.White.copy(alpha = 0.8f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Text("$index", style = MaterialTheme.typography.labelLarge, color = CamDark)
        }
    }

    @Composable
    private fun Overlay(title: String, body: String, unlocked: Boolean) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (unlocked) Palette.Teal else Palette.ChocolateDeep.copy(alpha = 0.85f))
        ) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (unlocked) title.replace(". ", ".\n") else title,
                    style = if (unlocked) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineMedium,
                    color = Palette.Cream,
                    textAlign = TextAlign.Center,
                )
                if (unlocked) {
                    // the happiest moment in the app: all seven windings, and the sun laughing
                    Spacer(Modifier.height(14.dp))
                    WindingDots(WindingDetector.REQUIRED)
                }
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        body,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (unlocked) Palette.Lime else Palette.PaperDeep,
                        textAlign = TextAlign.Center,
                    )
                }
                if (unlocked) {
                    Spacer(Modifier.height(12.dp))
                    SunMascot(SunMood.HAPPY, Modifier.size(width = 230.dp, height = 170.dp), hill = Palette.TealSoft)
                } else {
                    Spacer(Modifier.height(12.dp))
                    SunMascot(SunMood.WORRIED, Modifier.size(width = 180.dp, height = 130.dp), hill = null)
                }
            }
        }
    }

    /** Seven dots, one per winding, filling in gold as the detector sees them. */
    @Composable
    private fun WindingDots(bands: Int) {
        Row(
            Modifier.clearAndSetSemantics { contentDescription = "זוהו $bands מתוך ${WindingDetector.REQUIRED} כריכות" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(WindingDetector.REQUIRED) { i ->
                val on = i < bands
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (on) Palette.Gold else Color.Transparent)
                        .border(1.5.dp, if (on) Palette.Gold else Palette.Paper.copy(alpha = 0.7f), CircleShape)
                )
            }
        }
    }

    /** A dashed forearm lying across the frame: elbow on one side, wrist and hand on the other. */
    @Composable
    private fun ArmGuide(modifier: Modifier) {
        Canvas(modifier) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val elbow = h * 0.30f
            val wrist = h * 0.19f
            val arm = Path().apply {
                moveTo(w * 0.04f, cy - elbow)
                cubicTo(w * 0.35f, cy - elbow * 1.05f, w * 0.62f, cy - wrist * 1.15f, w * 0.80f, cy - wrist)
                lineTo(w * 0.80f, cy + wrist)
                cubicTo(w * 0.62f, cy + wrist * 1.15f, w * 0.35f, cy + elbow * 1.05f, w * 0.04f, cy + elbow)
                close()
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()), 0f)
            val line = Palette.Paper.copy(alpha = 0.75f)
            drawPath(arm, line, style = Stroke(width = 2.dp.toPx(), pathEffect = dash, cap = StrokeCap.Round))
            // the hand: a dashed rounded end past the wrist
            drawOval(
                line,
                topLeft = Offset(w * 0.80f, cy - wrist * 1.25f),
                size = Size(w * 0.16f, wrist * 2.5f),
                style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
            )
            // where the windings go, as faint slanted marks
            val mark = Palette.Gold.copy(alpha = 0.55f)
            for (k in 0 until 7) {
                val x = w * (0.16f + k * 0.085f)
                val half = elbow - (elbow - wrist) * (x / (w * 0.80f))
                drawLine(
                    mark, Offset(x - 6.dp.toPx(), cy + half * 0.8f), Offset(x + 6.dp.toPx(), cy - half * 0.8f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
    }

    @Composable
    private fun CameraPreview(facing: Int) {
        val context = LocalContext.current
        val previewView = remember {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        }
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        DisposableEffect(facing) {
            val future = ProcessCameraProvider.getInstance(context)
            var provider: ProcessCameraProvider? = null
            future.addListener({
                try {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor) { image -> onFrame(image) } }
                    val selector = CameraSelector.Builder().requireLensFacing(facing).build()
                    p.unbindAll()
                    cameraRef.value = p.bindToLifecycle(this@VerifyActivity, selector, preview, analysis)
                } catch (e: Exception) {
                    phase.value = Phase.Failed("המצלמה לא נפתחה. סגור אפליקציות אחרות שמשתמשות במצלמה, ונסה שוב.")
                }
            }, ContextCompat.getMainExecutor(context))

            onDispose {
                provider?.unbindAll()
            }
        }
    }
}
