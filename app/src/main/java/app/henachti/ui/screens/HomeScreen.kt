package app.henachti.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.henachti.data.Prefs
import app.henachti.domain.DayPolicy
import app.henachti.domain.IsraelTime
import app.henachti.domain.LockState
import app.henachti.domain.TamperReason
import app.henachti.domain.UnlockReason
import app.henachti.service.findActivity
import app.henachti.ui.CardShape
import app.henachti.ui.GoldButton
import app.henachti.ui.Hairline
import app.henachti.ui.MilestoneCard
import app.henachti.ui.Palette
import app.henachti.ui.SectionRow
import app.henachti.ui.SkyArc
import app.henachti.ui.SnoozeDots
import app.henachti.ui.StreakCalendar
import app.henachti.ui.SunMood
import app.henachti.ui.TamperUi
import app.henachti.ui.dayPhase
import app.henachti.ui.phaseColors
import app.henachti.ui.restDayGreeting
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * Home. A dark panel: a greeting for the hour, the half circle of today's tefillin window with the
 * sun walking on it (its face is the day's state), one big word or number, one sentence, one gold
 * button. Below: the streak card (it opens the dot calendar) and the lock row. The siddur and the
 * dedication live in the bottom bar.
 */
@Composable
fun HomeScreen(
    prefs: Prefs,
    policy: DayPolicy,
    state: LockState,
    now: Long,
    running: Boolean,
    onVerify: () -> Unit,
    onPrayers: () -> Unit,
    onDedication: () -> Unit,
    onSettings: () -> Unit,
    onFix: (TamperReason) -> Unit,
) {
    val armed = prefs.setupDone
    val streak = remember(now) { policy.streak(now) }
    var milestoneSeen by remember { mutableIntStateOf(prefs.milestoneSeen) }
    var calendarOpen by remember { mutableStateOf(false) }
    LightStatusIcons()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        DayPanel(prefs, policy, state, now, onVerify, onPrayers, onSettings, onFix)

        Column(Modifier.padding(horizontal = 20.dp)) {
            MilestoneCard(
                streak = streak,
                seen = milestoneSeen,
                onDismiss = { reached ->
                    prefs.milestoneSeen = reached
                    milestoneSeen = reached
                },
                modifier = Modifier.padding(top = 20.dp),
            )

            Spacer(Modifier.height(20.dp))
            StreakCard(streak, calendarOpen) { calendarOpen = !calendarOpen }
            if (calendarOpen) {
                StreakCalendar(policy, prefs, now, Modifier.padding(start = 4.dp, end = 4.dp, top = 18.dp))
            }

            Spacer(Modifier.height(16.dp))
            Hairline()
            SectionRow(
                title = when {
                    !armed -> "המנעול לא דרוך"
                    running -> "המנעול דרוך"
                    else -> "המנעול דרוך, השירות ישן"
                },
                subtitle = when {
                    !armed -> "בלי דריכה הטלפון לא ננעל בבוקר"
                    running -> "נפתח רק אחרי התפילין של היום"
                    else -> "כאן מעירים אותו"
                },
                onClick = onSettings,
                dot = if (armed && running) Palette.Success else Palette.GoldDeep,
            )
            Hairline()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The panel is dark, so while Home is shown the status-bar icons turn light. */
@Composable
private fun LightStatusIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        onDispose { controller?.isAppearanceLightStatusBars = true }
    }
}

@Composable
private fun DayPanel(
    prefs: Prefs,
    policy: DayPolicy,
    state: LockState,
    now: Long,
    onVerify: () -> Unit,
    onPrayers: () -> Unit,
    onSettings: () -> Unit,
    onFix: (TamperReason) -> Unit,
) {
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(IsraelTime.zone).toLocalDate() }
    // keyed on `now`, so a city change in Settings shows up on the next refresh
    val misheyakir = remember(now) { policy.misheyakir(today) }
    val sunset = remember(now) { policy.sunset(today) }
    val sunrise = remember(now) { policy.sunrise(today) }
    val phase = dayPhase(now, misheyakir, sunset)
    val name = prefs.userName
    val greeting = if (name.isBlank()) policy.greeting(now) else "${policy.greeting(now)}, $name"
    val look = lookFor(policy, prefs, state, now, today, misheyakir, sunset, onVerify, onPrayers, onSettings, onFix)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
            .background(Brush.verticalGradient(phaseColors(phase)))
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // the greeting of the hour in the middle, settings at the end; an equal space at the start keeps it centred
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(greeting, style = MaterialTheme.typography.headlineMedium, color = Palette.Cream, textAlign = TextAlign.Center)
                Text(policy.hebrewDate(state.day), style = MaterialTheme.typography.labelMedium, color = Palette.GoldSoft)
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, Palette.Cream.copy(alpha = 0.22f), CircleShape)
                    .clickable(role = Role.Button, onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = "הגדרות", tint = Palette.Cream, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(210.dp)
        ) {
            SkyArc(
                misheyakir, sunset, now,
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics {
                        contentDescription =
                            "זמן ההנחה היום: מ-${IsraelTime.hhmm(misheyakir)} עד השקיעה ב-${IsraelTime.hhmm(sunset)}"
                    },
                mood = look.mood,
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state is LockState.Snoozed) SnoozeClock(state.until)
                else Text(look.big, style = MaterialTheme.typography.displayLarge, color = Palette.Cream, textAlign = TextAlign.Center)
                Text(look.label, style = MaterialTheme.typography.titleSmall, color = Palette.GoldSoft, textAlign = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                "משיכיר ${IsraelTime.hhmm(misheyakir)} · זריחה ${IsraelTime.hhmm(sunrise)}",
                style = MaterialTheme.typography.labelMedium, color = Palette.Sand.copy(alpha = 0.85f),
            )
            Spacer(Modifier.weight(1f))
            Text("שקיעה ${IsraelTime.hhmm(sunset)}", style = MaterialTheme.typography.labelMedium, color = Palette.Sand.copy(alpha = 0.85f))
        }

        Spacer(Modifier.height(20.dp))
        Text(look.sentence, style = MaterialTheme.typography.headlineMedium, color = Palette.Cream, textAlign = TextAlign.Center)
        look.body?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Sand, textAlign = TextAlign.Center)
        }
        look.action?.let { a ->
            Spacer(Modifier.height(18.dp))
            GoldButton(a.text, a.onClick, Modifier.fillMaxWidth())
        }
        val snooze = when (state) {
            is LockState.Locked -> if (state.snoozesPerDay > 0) state.snoozesLeft to state.snoozesPerDay else null
            is LockState.Snoozed -> (state.max - state.used).coerceAtLeast(0) to state.max
            else -> null
        }
        snooze?.let { (left, max) ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                SnoozeDots(left, max)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (left) {
                        0 -> "הדחיות של היום נגמרו"
                        1 -> "נשארה דחייה אחת של חצי שעה"
                        else -> "נשארו $left דחיות של חצי שעה"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.Sand,
                )
            }
        }
    }
}

/** What the panel says for a state: the big word, the small label under it, the sun's mood, one sentence, one button. */
private class Look(
    val label: String,
    val big: String,
    val mood: SunMood,
    val sentence: String,
    val body: String? = null,
    val action: Action? = null,
)

private class Action(val text: String, val onClick: () -> Unit)

private fun countdown(now: Long, sunset: Long): Pair<String, String> {
    val totalMin = (((sunset - now) + 59_999L) / 60_000L).toInt()
    return when {
        totalMin <= 0 -> "השמש כבר שקעה" to "נעול"
        totalMin >= 60 -> "שעות עד השקיעה" to String.format(Locale.US, "%d:%02d", totalMin / 60, totalMin % 60)
        else -> "דקות עד השקיעה" to "$totalMin"
    }
}

private fun lookFor(
    policy: DayPolicy,
    prefs: Prefs,
    state: LockState,
    now: Long,
    today: LocalDate,
    misheyakir: Long,
    sunset: Long,
    onVerify: () -> Unit,
    onPrayers: () -> Unit,
    onSettings: () -> Unit,
    onFix: (TamperReason) -> Unit,
): Look {
    val night = now < misheyakir || now >= sunset
    return when (state) {
        is LockState.Tampered -> Look(
            label = "משהו כובה", big = "רגע", mood = SunMood.WORRIED,
            sentence = TamperUi.title(state.reason), body = TamperUi.body(state.reason),
            action = Action(TamperUi.action(state.reason)) { onFix(state.reason) },
        )
        is LockState.Snoozed -> Look(
            label = "עד שהמנעול חוזר", big = "", mood = SunMood.SLEEPY,
            sentence = "הכול פתוח עד ${IsraelTime.hhmm(state.until)}, ואז המנעול חוזר.",
            action = Action("לאימות במצלמה", onVerify),
        )
        is LockState.Locked -> {
            val (label, big) = countdown(now, sunset)
            Look(label, big, SunMood.WAITING, "התפילין מחכות.", action = Action("לאימות במצלמה", onVerify))
        }
        is LockState.Unlocked -> when (state.reason) {
            UnlockReason.VERIFIED -> Look(
                label = if (prefs.verifiedAt > 0L) "הנחת היום ב-${IsraelTime.hhmm(prefs.verifiedAt)}" else "הנחת היום",
                big = "פתוח", mood = if (night) SunMood.NIGHT else SunMood.HAPPY,
                sentence = "הטלפון פתוח עד מחר בבוקר.",
                action = Action("לסידור", onPrayers),
            )
            UnlockReason.BEFORE_START -> {
                val (label, big) = countdown(now, sunset)
                Look(
                    label, big, SunMood.WAITING,
                    "המנעול נדרך ב-${IsraelTime.hhmm(policy.lockStart(state.day))}. אפשר כבר לאמת.",
                    action = Action("לאימות במצלמה", onVerify),
                )
            }
            UnlockReason.NO_TEFILLIN_DAY -> Look(
                label = "אין הנחה היום",
                big = restDayGreeting(policy.noTefillinReason(state.day), afterSunset = now >= policy.sunset(state.day)),
                mood = if (night) SunMood.NIGHT else SunMood.CALM,
                sentence = "המנעול נח היום.",
            )
            UnlockReason.SHABBAT_BEGAN -> Look("השמש שקעה", "שבת שלום", SunMood.NIGHT, "המנעול נח עד מוצאי שבת.")
            UnlockReason.AFTER_SUNSET -> {
                val next = if (now < misheyakir) misheyakir else policy.misheyakir(today.plusDays(1))
                Look(
                    label = if (now < misheyakir) "אפשר להניח מהשעה הזאת" else "מחר אפשר להניח מהשעה הזאת",
                    big = IsraelTime.hhmm(next), mood = SunMood.NIGHT,
                    sentence = "זמן התפילין של היום עבר.",
                )
            }
            UnlockReason.SETUP_INCOMPLETE -> Look(
                "המנעול עוד לא דרוך", "שלום", SunMood.WAITING,
                "בלי דריכה הטלפון לא ננעל בבוקר.",
                action = Action("לדריכת המנעול", onSettings),
            )
        }
    }
}

/** The snooze's half hour as a big live clock. */
@Composable
private fun SnoozeClock(until: Long) {
    var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (true) {
            t = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val left = (until - t).coerceAtLeast(0L)
    Text(
        String.format(Locale.US, "%d:%02d", left / 60_000L, (left / 1_000L) % 60L),
        style = MaterialTheme.typography.displayLarge,
        color = Palette.Cream,
        textAlign = TextAlign.Center,
    )
}

/** The streak, one card on its own: the number in a gold ring; tapping opens the dot calendar. */
@Composable
private fun StreakCard(streak: Int, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Palette.CreamDeep)
            .border(1.dp, Palette.Sand, CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (streak == 1) "יום אחד ברצף" else "$streak ימים ברצף", style = MaterialTheme.typography.headlineMedium, color = Palette.Ink)
            Text(
                if (open) "לחיצה סוגרת את הלוח" else "לחיצה פותחת את הלוח. שבת וחג לא שוברים את הרצף",
                style = MaterialTheme.typography.labelMedium, color = Palette.Muted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(64.dp)
                .border(5.dp, Palette.Gold, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$streak", style = MaterialTheme.typography.headlineMedium, color = Palette.GoldText)
        }
    }
}
