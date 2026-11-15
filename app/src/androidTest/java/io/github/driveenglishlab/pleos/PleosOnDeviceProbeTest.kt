package io.github.driveenglishlab.pleos

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.driveenglishlab.pleos.domain.VehiclePosition
import io.github.driveenglishlab.pleos.platform.PublicVehicleStateSource
import io.github.driveenglishlab.pleos.platform.WindowAccessSource
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Neutral, opt-in SDK/service observation only. No project approval, product completion,
 * physical audibility, raw power, Auth, Room, lesson, or production DI is created or inferred.
 * Public reflection signatures were checked against cached TextToSpeech 3.3.1 classes.jar.
 * SDK callbacks are untagged: each isolated instance has one request and one immutable mailbox.
 */
@SdkSuppress(minSdkVersion = 34)
@RunWith(AndroidJUnit4::class)
class PleosOnDeviceProbeTest {
    private enum class Event { START, DONE, STOP, ERROR, FOCUS_LOST, CONDITIONS_LOST }
    private enum class Result {
        COMPLETED, STOP_OBSERVED, FOCUS_DENIED, FOCUS_LOST, INITIALIZATION_FAILED,
        INITIALIZATION_TIMEOUT, OUTPUT_TIMEOUT, SDK_ERROR, UNMATCHED_DONE, DONE_BEFORE_STOP,
        UNEXPECTED_STOP, SDK_OR_SERVICE_UNAVAILABLE, CONDITIONS_LOST, DONE_AFTER_STOP_REQUEST,
    }
    private enum class Phase {
        SETUP, REQUEST_FOCUS, LOAD_TYPES, CONSTRUCT, LOAD_EVENT_TYPE, CREATE_EVENT_PROXY,
        ADD_EVENT_LISTENER, LOAD_INITIALIZE_TYPE, CREATE_INITIALIZE_PROXY, INITIALIZE,
        WAIT_INITIALIZATION, SET_AUDIO_ATTRIBUTES, SET_LOCALE, SPEAK, WAIT_OUTPUT, STOP_REQUEST, CLEANUP,
    }
    private data class Observation(val result: Result, val phase: Phase, val exceptionClass: String?, val cleanupExceptionClass: String?, val initializeConfirmed: Boolean, val listenerRegistered: Boolean, val startObserved: Boolean,
        val stopCalled: Boolean, val listenerRemoved: Boolean, val released: Boolean, val focusReturned: Boolean)

    @Test fun observeOnlyActualOnDeviceEnglishKoreanAndStopWithoutRegistrationOrProductApproval() = runBlocking {
        assumeTrue("Requires explicit runPleosOnDeviceProbe=true",
            InstrumentationRegistry.getArguments().getString("runPleosOnDeviceProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals("Must use actual product target app, not the library test package", "io.github.driveenglishlab.pleos", context.packageName)
        assertEquals("Probe is scoped to the actual target35 app", 35, context.applicationInfo.targetSdkVersion)
        assertEquals("Permission presence is required but is not project approval", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("pleos.car.permission.TTS_SERVICE"))
        // A neutral test Activity provides a real foreground lifetime without starting product Main/Auth.
        ActivityScenario.launch(ComponentActivity::class.java).use { foreground ->
            assertEquals(Lifecycle.State.RESUMED,foreground.state)
            val english = observe(context, Locale.US, "This is an on device speech test.", stopAtStart = false)
            val korean = observe(context, Locale.KOREA, "기기 내 음성 시험입니다.", stopAtStart = false)
            val stop = observe(context, Locale.US, "This separate speech test checks the stop callback.", stopAtStart = true)
            for ((name, value) in listOf("english" to english, "korean" to korean, "stop" to stop)) {
                // Fixed categories only; no exception messages, credentials, IDs, or application data.
                Log.i("PleosOnDeviceProbe", "$name result=${value.result}, phase=${value.phase}, exceptionClass=${value.exceptionClass ?: "none"}, cleanupExceptionClass=${value.cleanupExceptionClass ?: "none"}, initializeConfirmed=${value.initializeConfirmed}, listenerRegistered=${value.listenerRegistered}, start=${value.startObserved}, stopCalled=${value.stopCalled}, listenerRemoved=${value.listenerRemoved}, released=${value.released}, focusReturned=${value.focusReturned}")
            }
            assertEquals("English ON_DEVICE instance must report its own start and done", Result.COMPLETED, english.result)
            assertEquals("Korean ON_DEVICE instance must report its own start and done", Result.COMPLETED, korean.result)
            assertEquals("Stop is a separate actual callback observation, not inferred from done/release", Result.STOP_OBSERVED, stop.result)
            for (value in listOf(english, korean, stop)) {
                assertTrue(value.initializeConfirmed); assertTrue(value.listenerRegistered)
                assertTrue(value.stopCalled); assertTrue(value.listenerRemoved); assertTrue(value.released); assertTrue(value.focusReturned)
            }
        }
    }

    /** This is one bounded native observation, not production Gateway/vehicle approval. */
    @Test fun observeStoppedAThenNewInstanceBWithOriginalCallbackLedger() = runBlocking {
        assumeTrue("Requires explicit runPleosOnDevicePairProbe=true",
            InstrumentationRegistry.getArguments().getString("runPleosOnDevicePairProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        assertEquals("io.github.driveenglishlab.pleos", context.packageName)
        assertEquals(35, context.applicationInfo.targetSdkVersion)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission("pleos.car.permission.TTS_SERVICE"))
        val ledger = PairLedger()
        val setupStage = AtomicReference("SCENARIO_LAUNCH")
        val runnerThread = Thread.currentThread()
        fun setup(value: String) { setupStage.set(value); Log.i("PleosOnDevicePair", "setup stage=$value") }
        val setupObserver = Executors.newSingleThreadScheduledExecutor()
        val setupWatch = setupObserver.schedule({
            val stacks = Thread.getAllStackTraces()
            fun describe(thread: Thread?): String {
                if (thread == null) return "ABSENT"
                val frames = stacks[thread].orEmpty()
                val own = frames.firstOrNull { it.className.startsWith("io.github.driveenglishlab.pleos.") }
                val category = when {
                    frames.any { it.className.contains("ActivityScenario") } -> "ACTIVITY_SCENARIO"
                    frames.any { it.className == "android.os.BinderProxy" } -> "PUBLIC_BINDER_CALL"
                    frames.any { it.className == "android.app.Instrumentation" && it.methodName == "runOnMainSync" } -> "MAIN_SYNC"
                    frames.any { it.className == "android.os.MessageQueue" && it.methodName == "nativePollOnce" } -> "MAIN_LOOP"
                    frames.any { it.className.startsWith("kotlinx.coroutines.") } -> "COROUTINE_WAIT"
                    else -> "OTHER"
                }
                val method = own?.methodName?.takeIf { it.length <= 120 && it.matches(Regex("[A-Za-z0-9_$<>]+")) } ?: "NONE"
                return "${thread.state}/$category/ownMethod=$method"
            }
            Log.i("PleosOnDevicePair", "setupWatch stage=${setupStage.get()} main=${describe(stacks.keys.firstOrNull { it.name == "main" })} runner=${describe(runnerThread)}")
        }, 10, TimeUnit.SECONDS)
        val sourceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val vehicle = PublicVehicleStateSource(context, sourceScope)
        try {
            setup("SCENARIO_LAUNCH")
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                setup("SCENARIO_RETURNED")
                lateinit var window: WindowAccessSource
                lateinit var focusListener: ViewTreeObserver.OnWindowFocusChangeListener
                lateinit var lifecycleObserver: DefaultLifecycleObserver
                val resumed = MutableStateFlow(false)
                setup("WINDOW_OBSERVER_SCHEDULE")
                scenario.onActivity { activity ->
                    setup("WINDOW_OBSERVER_ENTER")
                    window = WindowAccessSource(activity)
                    fun refresh() { window.refresh(); resumed.value = activity.lifecycle.currentState == Lifecycle.State.RESUMED }
                    focusListener = ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
                    lifecycleObserver = object : DefaultLifecycleObserver {
                        override fun onResume(owner: LifecycleOwner) = refresh()
                        override fun onPause(owner: LifecycleOwner) = refresh()
                        override fun onStop(owner: LifecycleOwner) { window.closed(); resumed.value = false }
                    }
                    activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
                    activity.lifecycle.addObserver(lifecycleObserver)
                    refresh()
                    setup("WINDOW_OBSERVER_READY")
                }
                fun allowed(): Boolean {
                    val value = vehicle.state.value
                    val components = value.components
                    return resumed.value && window.state.value.visible && value.status == "connected" &&
                        value.safety.position == VehiclePosition.PARKED && value.safety.uxKnown && value.safety.detailsAllowed &&
                        listOf(components.audio, components.media).all { it.known && it.allowed && it.sourceGeneration == components.generation }
                }
                val conditions = combine(vehicle.state, window.state, resumed) { _, _, _ -> allowed() }
                fun environment(label: String) {
                    val value = vehicle.state.value
                    Log.i("PleosOnDevicePair", "$label package=${context.packageName} target=${context.applicationInfo.targetSdkVersion} api=${Build.VERSION.SDK_INT} configuredSdk=3.3.1 mode=ON_DEVICE ttsPermission=true resumed=${resumed.value} visible=${window.state.value.visible} windowFocused=${window.state.value.focused} position=${value.safety.position} uxKnown=${value.safety.uxKnown} uxDetails=${value.safety.detailsAllowed} actualPower=${value.actualPower} componentGeneration=${value.components.generation} audioKnown=${value.components.audio.known} audioAllowed=${value.components.audio.allowed} audioSource=${value.components.audio.sourceGeneration} audioPermission=${value.components.audio.permissionGeneration} mediaKnown=${value.components.media.known} mediaAllowed=${value.components.media.allowed} mediaSource=${value.components.media.sourceGeneration} mediaPermission=${value.components.media.permissionGeneration} microphoneKnown=${value.components.microphone.known} microphoneAllowed=${value.components.microphone.allowed}")
                }
                try {
                    setup("VEHICLE_START_SCHEDULE")
                    withContext(Dispatchers.Main.immediate) { setup("VEHICLE_START_ENTER"); vehicle.start(); setup("VEHICLE_START_RETURN") }
                    setup("WAIT_ACTUAL_CONDITIONS")
                    val ready = withTimeoutOrNull(10_000) { conditions.first { it } } == true
                    environment("BEFORE_A")
                    assertTrue("Actual current foreground/P/UXR/AUDIO/MEDIA conditions required; no synthetic grant", ready && allowed())
                    setup("SDK_SEQUENCE")
                    setupWatch.cancel(false)
                    val a = ledger.run("A")
                    val stopped = observe(context, Locale.US,
                        "This is the first isolated on device speech request. The public stop command will interrupt this short neutral message after its start event.",
                        true, a, ::allowed, conditions)
                    logPairObservation("A", stopped)
                    // Never construct B after failed/unknown A output or cleanup.
                    assertEquals("A requires its own actual STOP after START/public stop", Result.STOP_OBSERVED, stopped.result)
                    assertTrue("A cleanup calls and actual focus return must be confirmed before B", clean(stopped))
                    assertTrue(stopped.stopCalled)
                    assertTrue(allowed())
                    environment("BEFORE_B")
                    val b = ledger.run("B")
                    val completed = observe(context, Locale.US,
                        "This is a new isolated speech request after the first request stopped.",
                        false, b, ::allowed, conditions)
                    logPairObservation("B", completed)
                    assertEquals("B requires its own actual START and DONE", Result.COMPLETED, completed.result)
                    assertTrue("B cleanup must return actual focus", clean(completed))
                    ledger.assertDistinctBindings()
                } finally {
                    ledger.finishAndLog()
                    scenario.onActivity { activity ->
                        activity.lifecycle.removeObserver(lifecycleObserver)
                        activity.window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
                    }
                }
            }
        } finally {
            setupWatch.cancel(false)
            setupObserver.shutdownNow()
            withContext(NonCancellable + Dispatchers.Main.immediate) { vehicle.close() }
            sourceScope.cancel()
        }
    }

    private fun clean(value: Observation) = value.initializeConfirmed && value.listenerRegistered && value.startObserved &&
        value.listenerRemoved && value.released && value.focusReturned && value.cleanupExceptionClass == null

    private fun logPairObservation(label: String, value: Observation) {
        Log.i("PleosOnDevicePair", "$label result=${value.result} phase=${value.phase} exceptionClass=${value.exceptionClass ?: "none"} cleanupExceptionClass=${value.cleanupExceptionClass ?: "none"} initialized=${value.initializeConfirmed} listenerRegistered=${value.listenerRegistered} start=${value.startObserved} stopCallReturned=${value.stopCalled} listenerRemoveReturned=${value.listenerRemoved} releaseReturned=${value.released} focusReturned=${value.focusReturned}")
    }

    private class ConditionsChanged : Exception()
    private class PairLedger {
        private data class Arrival(val run: RunTrace, val sequence: Long, val at: Long, val phase: Phase,
            val kind: String, val native: Boolean, val instanceBound: Boolean, var decision: String = "PENDING")
        private val sequence = AtomicLong()
        private val events = mutableListOf<Arrival>()
        private val runs = mutableListOf<RunTrace>()
        private val begin = SystemClock.elapsedRealtimeNanos()
        @Synchronized fun run(label: String): RunTrace = RunTrace(this, label, runs.size + 1).also { runs.add(it) }
        fun arrival(run: RunTrace, kind: String, native: Boolean, at: Long = SystemClock.elapsedRealtimeNanos()): Long {
            val seq = sequence.incrementAndGet()
            val arrival = Arrival(run, seq, at, run.phase.get(), kind, native, run.sdk != null)
            synchronized(this) {
                check(events.size < 512) { "Bounded native trace overflow" }
                events.add(arrival)
            }
            return seq
        }
        @Synchronized fun decision(seq: Long, value: String) { events.single { it.sequence == seq }.decision = value }
        @Synchronized fun assertDistinctBindings() {
            assertEquals(2, runs.size)
            val a = runs[0]; val b = runs[1]
            assertNotNull(a.sdk); assertNotNull(b.sdk)
            assertNotSame(a.sdk, b.sdk); assertNotSame(a.listener, b.listener); assertNotSame(a.initializer, b.initializer)
            assertNotSame(a.mailbox, b.mailbox)
            assertTrue(a.cleanupEnd > 0 && a.cleanupEnd <= b.begin)
        }
        fun finishAndLog() {
            // Freeze only the bounded observation window; do not hold a journal lock while logging.
            val (end, observed, bindings) = synchronized(this) {
                Triple(SystemClock.elapsedRealtimeNanos(), events.map { it.copy() }.sortedBy { it.sequence }, runs.toList())
            }
            for (event in observed) {
                val listenerKind = when { !event.native -> "APPLICATION"; event.kind.startsWith("INITIALIZE") -> "SDK_INITIALIZER"; event.kind.startsWith("FOCUS") -> "AUDIO_FOCUS"; else -> "SDK_EVENT" }
                Log.i("PleosOnDevicePair", "event request=${event.run.label} instanceSlot=${event.run.ordinal} instanceBound=${event.instanceBound} listenerKind=$listenerKind listenerSlot=${event.run.ordinal} mailboxSlot=${event.run.ordinal} sequence=${event.sequence} arrivalNs=${event.at} phase=${event.phase} kind=${event.kind} native=${event.native} admission=${event.decision}")
            }
            val a = bindings.firstOrNull()
            val afterCleanup = if (a == null || a.cleanupEnd == 0L) emptyList() else observed.filter { it.native && it.run === a && it.at >= a.cleanupEnd && it.at <= end }
            val late = afterCleanup.filter { it.kind in setOf("START", "DONE", "STOP", "ERROR") }
            Log.i("PleosOnDevicePair", "window beginNs=$begin endNs=$end actualRuns=${bindings.size} aSdkEventsAfterCleanup=${late.size} aSdkEventsAfterCleanupAdmitted=${late.count { it.decision == "ADMITTED" }} aLateAdmissionUnknown=${late.count { it.decision == "PENDING" }} aInitializerAfterCleanup=${afterCleanup.count { it.kind.startsWith("INITIALIZE") }} aFocusCallbacksAfterCleanup=${afterCleanup.count { it.kind.startsWith("FOCUS") }} lateSdkRace=${if (late.isEmpty()) "NOT_OBSERVED" else "OBSERVED"} initTimeoutMs=10000 outputTimeoutMs=10000 cleanupMeans=PUBLIC_CALL_RETURN standaloneOnly=true")
            assertTrue("A native SDK events after cleanup must never be admitted", late.none { it.decision == "ADMITTED" })
        }
    }

    private class RunTrace(val ledger: PairLedger, val label: String, val ordinal: Int) {
        val begin = SystemClock.elapsedRealtimeNanos()
        val phase = AtomicReference(Phase.SETUP)
        var sdk: Any? = null
        var listener: Any? = null
        var initializer: Any? = null
        var mailbox: Any? = null
        @Volatile var cleanupEnd = 0L
        private val stopRequestedAt = AtomicLong()
        val doneBeforeStop = AtomicReference<Boolean?>(null)
        fun raw(kind: String): Long {
            val at = SystemClock.elapsedRealtimeNanos()
            if (kind == "DONE") doneBeforeStop.compareAndSet(null, stopRequestedAt.get() == 0L)
            return ledger.arrival(this, kind, true, at)
        }
        fun decision(seq: Long, value: String) = ledger.decision(seq, value)
        fun action(kind: String, disposition: String = "CALL") {
            val at = SystemClock.elapsedRealtimeNanos()
            if (kind == "STOP") stopRequestedAt.compareAndSet(0L, at)
            val seq = ledger.arrival(this, kind, false, at); ledger.decision(seq, disposition)
        }
    }

    private suspend fun observe(context: Context, locale: Locale, text: String, stopAtStart: Boolean,
        trace: RunTrace? = null, allowed: () -> Boolean = { true }, conditions: Flow<Boolean>? = null): Observation {
        val events = Channel<Event>(Channel.UNLIMITED)
        trace?.mailbox = events
        val terminalSeen = AtomicBoolean(false)
        val conditionWatcher = conditions?.let { changes -> CoroutineScope(currentCoroutineContext()).launch {
            changes.collect { if (!it) events.trySend(Event.CONDITIONS_LOST) }
        } }
        val initialized = CompletableDeferred<Boolean>()
        val focusOwned = AtomicBoolean(false)
        val accepting = AtomicBoolean(true)
        val synthesisRequested = AtomicBoolean(false)
        val audio = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ value ->
                val seq = trace?.raw(if (value == AudioManager.AUDIOFOCUS_GAIN) "FOCUS_GAIN" else "FOCUS_LOSS")
                val admit = accepting.get()
                if (admit) {
                    val granted = value == AudioManager.AUDIOFOCUS_GAIN
                    focusOwned.set(granted)
                    if (!granted) events.trySend(Event.FOCUS_LOST)
                }
                seq?.let { trace.decision(it, if (admit) "ADMITTED" else "CLOSED_ORIGINAL_RUN") }
            }, Handler(Looper.getMainLooper())).build()
        var sdk: Any? = null
        var eventListener: Any? = null
        var eventType: Class<*>? = null
        var result = Result.SDK_OR_SERVICE_UNAVAILABLE
        var phase = Phase.SETUP
        var outcomePhase = phase
        var focusRequestAttempted = false
        fun stage(value: Phase) { phase = value; trace?.phase?.set(value) }
        fun requireCurrentConditions() { if (!allowed()) throw ConditionsChanged() }
        var exceptionClass: String? = null
        var cleanupExceptionClass: String? = null
        val targetLoader = context.classLoader
        fun load(name: String): Class<*> = Class.forName(name, true, targetLoader)
        var initializeConfirmed = false
        var listenerRegistered = false
        var started = false
        var stopCalled = false
        var removed = false
        var released = false
        var returned = false
        try {
            requireCurrentConditions()
            stage(Phase.REQUEST_FOCUS)
            trace?.action("REQUEST_AUDIO_FOCUS")
            val granted = withContext(Dispatchers.Main.immediate) {
                focusRequestAttempted = true
                (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED).also { focusOwned.set(it) }
            }
            trace?.action("REQUEST_AUDIO_FOCUS_RETURN", if (granted) "GRANTED" else "DENIED")
            trace?.action("MEDIA_ATTRIBUTES", "USAGE_${attributes.usage}_CONTENT_${attributes.contentType}")
            if (!granted) result = Result.FOCUS_DENIED
            else {
                // No registerApp, server/HYBRID mode, credentials, or Android TTS fallback.
                withContext(Dispatchers.Main.immediate) {
                    requireCurrentConditions()
                    stage(Phase.LOAD_TYPES)
                    val type = load("ai.pleos.playground.tts.TextToSpeech")
                    val modeType = load("ai.pleos.playground.tts.constant.Mode")
                    val mode = requireNotNull(modeType.enumConstants).single { (it as? Enum<*>)?.name == "ON_DEVICE" }
                    stage(Phase.CONSTRUCT)
                    val instance = type.getConstructor(Context::class.java, modeType).newInstance(context, mode)
                    sdk = instance
                    trace?.sdk = instance
                    trace?.action("CONSTRUCT_NEW_ON_DEVICE", "RETURNED")
                    stage(Phase.LOAD_INITIALIZE_TYPE)
                    val initType = load("ai.pleos.playground.tts.listener.OnInitializeListener")
                    stage(Phase.CREATE_INITIALIZE_PROXY)
                    val init = proxy(initType) { name ->
                        val success = when (name) { "onSuccess" -> true; "onFailure" -> false; else -> null }
                        if (success != null) {
                            val seq = trace?.raw(if (success) "INITIALIZE_SUCCESS" else "INITIALIZE_FAILURE")
                            val admit = accepting.get() && allowed() && initialized.complete(success)
                            seq?.let { trace.decision(it, if (admit) "ADMITTED" else "CLOSED_CONDITIONS_OR_DUPLICATE") }
                        }
                    }
                    trace?.initializer = init
                    stage(Phase.INITIALIZE)
                    trace?.action("INITIALIZE")
                    type.getMethod("initialize", initType).invoke(instance, init)
                    trace?.action("INITIALIZE_RETURN", "RETURNED")
                }
                stage(Phase.WAIT_INITIALIZATION)
                val initialization = withTimeoutOrNull(10_000) { initialized.await() }
                initializeConfirmed = initialization == true
                when (initialization) {
                    null -> result = Result.INITIALIZATION_TIMEOUT
                    false -> result = Result.INITIALIZATION_FAILED
                    true -> if (!focusOwned.get()) result = Result.FOCUS_LOST else {
                        withContext(Dispatchers.Main.immediate) {
                            requireCurrentConditions()
                            check(focusOwned.get())
                            val instance = requireNotNull(sdk)
                            stage(Phase.LOAD_EVENT_TYPE)
                            val listenerType = load("ai.pleos.playground.tts.listener.EventListener")
                            eventType = listenerType
                            stage(Phase.CREATE_EVENT_PROXY)
                            val listener = proxy(listenerType) { name ->
                                val event = when (name) { "onStart" -> Event.START; "onDone" -> Event.DONE; "onStop" -> Event.STOP; "onError" -> Event.ERROR; else -> null }
                                if (event != null) {
                                    // Native arrival is observed on its original closure before any terminal/permission filter.
                                    val seq = trace?.raw(event.name)
                                    val reason = when {
                                        !accepting.get() -> "CLOSED_ORIGINAL_RUN"
                                        !synthesisRequested.get() -> "SPEAK_NOT_REQUESTED"
                                        !allowed() -> "CONDITIONS_LOST"
                                        trace != null && !focusOwned.get() -> "FOCUS_LOST"
                                        trace != null && terminalSeen.get() -> "AFTER_TERMINAL"
                                        trace != null && event != Event.START && !terminalSeen.compareAndSet(false, true) -> "DUPLICATE_TERMINAL"
                                        else -> if (events.trySend(event).isSuccess) "ADMITTED" else "ORIGINAL_MAILBOX_CLOSED"
                                    }
                                    seq?.let { trace.decision(it, reason) }
                                }
                            }
                            eventListener = listener
                            trace?.listener = listener
                            stage(Phase.ADD_EVENT_LISTENER)
                            instance.javaClass.getMethod("addEventListener", listenerType).invoke(instance, listener)
                            listenerRegistered = true
                            trace?.action("REGISTER_LISTENER_RETURN", "RETURNED")
                            stage(Phase.SET_AUDIO_ATTRIBUTES)
                            instance.javaClass.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(instance, attributes)
                            stage(Phase.SET_LOCALE)
                            instance.javaClass.getMethod("setLocale", Locale::class.java).invoke(instance, locale)
                            requireCurrentConditions()
                            synthesisRequested.set(true)
                            stage(Phase.SPEAK)
                            trace?.action("SPEAK")
                            instance.javaClass.getMethod("speak", String::class.java, java.lang.Boolean.TYPE).invoke(instance, text, true)
                            trace?.action("SPEAK_RETURN", "RETURNED")
                        }
                        stage(Phase.WAIT_OUTPUT)
                        result = withTimeoutOrNull(10_000) {
                            var terminal: Result? = null
                            while (terminal == null) when (events.receive()) {
                                Event.START -> {
                                    started = true
                                    if (stopAtStart && !stopCalled) withContext(Dispatchers.Main.immediate) {
                                        stage(Phase.STOP_REQUEST)
                                        requireCurrentConditions()
                                        trace?.action("STOP")
                                        requireNotNull(sdk).javaClass.getMethod("stop").invoke(sdk)
                                        stopCalled = true
                                        trace?.action("STOP_RETURN", "RETURNED")
                                    }
                                }
                                Event.DONE -> terminal = if (!started) Result.UNMATCHED_DONE else if (stopAtStart) {
                                    if (trace?.doneBeforeStop?.get() == false) Result.DONE_AFTER_STOP_REQUEST else Result.DONE_BEFORE_STOP
                                } else Result.COMPLETED
                                Event.STOP -> terminal = if (started && stopAtStart && stopCalled) Result.STOP_OBSERVED else Result.UNEXPECTED_STOP
                                Event.ERROR -> terminal = Result.SDK_ERROR
                                Event.FOCUS_LOST -> terminal = Result.FOCUS_LOST
                                Event.CONDITIONS_LOST -> terminal = Result.CONDITIONS_LOST
                            }
                            terminal
                        } ?: Result.OUTPUT_TIMEOUT
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            exceptionClass = exceptionNameOnly(failure)
            result = if (failure is ConditionsChanged) Result.CONDITIONS_LOST else Result.SDK_OR_SERVICE_UNAVAILABLE
        } finally {
            // Cleanup is process-local and runs even on timeout/cancellation; late callbacks cannot bind another request.
            outcomePhase = phase
            accepting.set(false)
            conditionWatcher?.cancel()
            stage(Phase.CLEANUP)
            trace?.action("CLOSE_ORIGINAL_ACCEPTANCE", "CLOSED")
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                sdk?.let { instance ->
                    // The SDK service is assigned only by successful initialize; skipped calls are not observed success.
                    val stopRequired = initializeConfirmed && (trace == null || synthesisRequested.get() && !stopCalled && result !in setOf(Result.COMPLETED, Result.STOP_OBSERVED))
                    if (stopRequired) try {
                        trace?.action("CLEANUP_STOP")
                        instance.javaClass.getMethod("stop").invoke(instance); stopCalled = true
                        trace?.action("CLEANUP_STOP_RETURN", "RETURNED")
                    } catch (failure: Exception) { trace?.action("CLEANUP_STOP_RETURN", "FAILED"); if (cleanupExceptionClass == null) cleanupExceptionClass = exceptionNameOnly(failure) }
                    else trace?.action("CLEANUP_STOP", "NOT_APPLICABLE")
                    if (listenerRegistered) try {
                        trace?.action("REMOVE_LISTENER")
                        instance.javaClass.getMethod("removeEventListener", requireNotNull(eventType)).invoke(instance, eventListener); removed = true
                        trace?.action("REMOVE_LISTENER_RETURN", "RETURNED")
                    } catch (failure: Exception) { trace?.action("REMOVE_LISTENER_RETURN", "FAILED"); if (cleanupExceptionClass == null) cleanupExceptionClass = exceptionNameOnly(failure) }
                    else trace?.action("REMOVE_LISTENER", "NOT_APPLICABLE")
                    try {
                        trace?.action("RELEASE")
                        instance.javaClass.getMethod("release").invoke(instance); released = true
                        trace?.action("RELEASE_RETURN", if (initializeConfirmed) "RETURNED" else "UNCONFIRMED_INITIALIZATION")
                    } catch (failure: Exception) { trace?.action("RELEASE_RETURN", "FAILED"); if (cleanupExceptionClass == null) cleanupExceptionClass = exceptionNameOnly(failure) }
                }
                if (trace == null || focusRequestAttempted) try {
                    trace?.action("ABANDON_FOCUS")
                    returned = audio.abandonAudioFocusRequest(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    trace?.action("ABANDON_FOCUS_RETURN", if (returned) "GRANTED" else "UNCONFIRMED")
                } catch (failure: Exception) {
                    trace?.action("ABANDON_FOCUS_RETURN", "FAILED")
                    if (cleanupExceptionClass == null) cleanupExceptionClass = exceptionNameOnly(failure)
                } else trace.action("ABANDON_FOCUS", "NOT_APPLICABLE")
                focusOwned.set(false)
            }
            events.close()
            trace?.action("ORIGINAL_MAILBOX_CLOSED", "CLOSED")
            trace?.cleanupEnd = SystemClock.elapsedRealtimeNanos()
        }
        return Observation(result, outcomePhase, exceptionClass, cleanupExceptionClass, initializeConfirmed, listenerRegistered, started, stopCalled, removed, released, returned)
    }

    /** Report only bounded class names. Never log Throwable.toString/message/arguments/stack. */
    private fun exceptionNameOnly(value: Throwable): String {
        var cause = value
        repeat(8) {
            val unwrapped = when (cause) {
                is java.lang.reflect.InvocationTargetException -> (cause as java.lang.reflect.InvocationTargetException).targetException
                is java.lang.reflect.UndeclaredThrowableException -> (cause as java.lang.reflect.UndeclaredThrowableException).undeclaredThrowable
                is ExceptionInInitializerError -> (cause as ExceptionInInitializerError).exception
                else -> null
            }
            if (unwrapped != null && unwrapped !== cause) cause = unwrapped
        }
        val name = cause.javaClass.name
        val permittedPackage = listOf("java.lang.", "java.util.", "android.", "kotlin.", "kotlinx.coroutines.", "ai.pleos.playground.", "com.android.").any(name::startsWith)
        return if (permittedPackage && name.length <= 180 && name.matches(Regex("[A-Za-z0-9_.$]+"))) name else "OTHER_THROWABLE"
    }

    private fun proxy(type: Class<*>, callback: (String) -> Unit): Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.firstOrNull()
            "toString" -> "PleosOnDeviceProbeListener"
            else -> { callback(method.name); null }
        }
    }
}
