package io.github.driveenglishlab.pleos

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
    private enum class Event { START, DONE, STOP, ERROR, FOCUS_LOST }
    private enum class Result {
        COMPLETED, STOP_OBSERVED, FOCUS_DENIED, FOCUS_LOST, INITIALIZATION_FAILED,
        INITIALIZATION_TIMEOUT, OUTPUT_TIMEOUT, SDK_ERROR, UNMATCHED_DONE, DONE_BEFORE_STOP,
        UNEXPECTED_STOP, SDK_OR_SERVICE_UNAVAILABLE,
    }
    private enum class Phase {
        SETUP, REQUEST_FOCUS, LOAD_TYPES, CONSTRUCT, LOAD_EVENT_TYPE, CREATE_EVENT_PROXY,
        ADD_EVENT_LISTENER, LOAD_INITIALIZE_TYPE, CREATE_INITIALIZE_PROXY, INITIALIZE,
        WAIT_INITIALIZATION, SET_AUDIO_ATTRIBUTES, SET_LOCALE, SPEAK, WAIT_OUTPUT, STOP_REQUEST,
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

    private suspend fun observe(context: Context, locale: Locale, text: String, stopAtStart: Boolean): Observation {
        val events = Channel<Event>(Channel.UNLIMITED)
        val initialized = CompletableDeferred<Boolean>()
        val focusOwned = AtomicBoolean(false)
        val accepting = AtomicBoolean(true)
        val synthesisRequested = AtomicBoolean(false)
        val audio = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ value ->
                if (accepting.get()) {
                    val granted = value == AudioManager.AUDIOFOCUS_GAIN
                    focusOwned.set(granted)
                    if (!granted) events.trySend(Event.FOCUS_LOST)
                }
            }, Handler(Looper.getMainLooper())).build()
        var sdk: Any? = null
        var eventListener: Any? = null
        var eventType: Class<*>? = null
        var result = Result.SDK_OR_SERVICE_UNAVAILABLE
        var phase = Phase.SETUP
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
            phase = Phase.REQUEST_FOCUS
            val granted = withContext(Dispatchers.Main.immediate) {
                (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED).also { focusOwned.set(it) }
            }
            if (!granted) result = Result.FOCUS_DENIED
            else {
                // No registerApp, server/HYBRID mode, credentials, or Android TTS fallback.
                withContext(Dispatchers.Main.immediate) {
                    phase = Phase.LOAD_TYPES
                    val type = load("ai.pleos.playground.tts.TextToSpeech")
                    val modeType = load("ai.pleos.playground.tts.constant.Mode")
                    val mode = requireNotNull(modeType.enumConstants).single { (it as? Enum<*>)?.name == "ON_DEVICE" }
                    phase = Phase.CONSTRUCT
                    val instance = type.getConstructor(Context::class.java, modeType).newInstance(context, mode)
                    sdk = instance
                    phase = Phase.LOAD_INITIALIZE_TYPE
                    val initType = load("ai.pleos.playground.tts.listener.OnInitializeListener")
                    phase = Phase.CREATE_INITIALIZE_PROXY
                    val init = proxy(initType) { name -> if (accepting.get()) when (name) {
                        "onSuccess" -> initialized.complete(true)
                        "onFailure" -> initialized.complete(false)
                    } }
                    phase = Phase.INITIALIZE
                    type.getMethod("initialize", initType).invoke(instance, init)
                }
                phase = Phase.WAIT_INITIALIZATION
                val initialization = withTimeoutOrNull(10_000) { initialized.await() }
                initializeConfirmed = initialization == true
                when (initialization) {
                    null -> result = Result.INITIALIZATION_TIMEOUT
                    false -> result = Result.INITIALIZATION_FAILED
                    true -> if (!focusOwned.get()) result = Result.FOCUS_LOST else {
                        withContext(Dispatchers.Main.immediate) {
                            check(focusOwned.get())
                            val instance = requireNotNull(sdk)
                            phase = Phase.LOAD_EVENT_TYPE
                            val listenerType = load("ai.pleos.playground.tts.listener.EventListener")
                            eventType = listenerType
                            phase = Phase.CREATE_EVENT_PROXY
                            val listener = proxy(listenerType) { name ->
                                if (accepting.get() && synthesisRequested.get()) when (name) {
                                    "onStart" -> events.trySend(Event.START)
                                    "onDone" -> events.trySend(Event.DONE)
                                    "onStop" -> events.trySend(Event.STOP)
                                    "onError" -> events.trySend(Event.ERROR)
                                }
                            }
                            eventListener = listener
                            phase = Phase.ADD_EVENT_LISTENER
                            instance.javaClass.getMethod("addEventListener", listenerType).invoke(instance, listener)
                            listenerRegistered = true
                            phase = Phase.SET_AUDIO_ATTRIBUTES
                            instance.javaClass.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(instance, attributes)
                            phase = Phase.SET_LOCALE
                            instance.javaClass.getMethod("setLocale", Locale::class.java).invoke(instance, locale)
                            synthesisRequested.set(true)
                            phase = Phase.SPEAK
                            instance.javaClass.getMethod("speak", String::class.java, java.lang.Boolean.TYPE).invoke(instance, text, true)
                        }
                        phase = Phase.WAIT_OUTPUT
                        result = withTimeoutOrNull(10_000) {
                            var terminal: Result? = null
                            while (terminal == null) when (events.receive()) {
                                Event.START -> {
                                    started = true
                                    if (stopAtStart && !stopCalled) withContext(Dispatchers.Main.immediate) {
                                        phase = Phase.STOP_REQUEST
                                        requireNotNull(sdk).javaClass.getMethod("stop").invoke(sdk)
                                        stopCalled = true
                                    }
                                }
                                Event.DONE -> terminal = if (!started) Result.UNMATCHED_DONE else if (stopAtStart) Result.DONE_BEFORE_STOP else Result.COMPLETED
                                Event.STOP -> terminal = if (started && stopAtStart && stopCalled) Result.STOP_OBSERVED else Result.UNEXPECTED_STOP
                                Event.ERROR -> terminal = Result.SDK_ERROR
                                Event.FOCUS_LOST -> terminal = Result.FOCUS_LOST
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
            result = Result.SDK_OR_SERVICE_UNAVAILABLE
        } finally {
            // Cleanup is process-local and runs even on timeout/cancellation; late callbacks cannot bind another request.
            accepting.set(false)
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                sdk?.let { instance ->
                    // The SDK service is assigned only by successful initialize; skipped calls are not observed success.
                    if (initializeConfirmed) try { instance.javaClass.getMethod("stop").invoke(instance); stopCalled = true } catch (failure: Exception) { if (cleanupExceptionClass == null) cleanupExceptionClass = exceptionNameOnly(failure) }
                    if (listenerRegistered) try { instance.javaClass.getMethod("removeEventListener", requireNotNull(eventType)).invoke(instance, eventListener); removed = true } catch (failure: Exception) { if (cleanupExceptionClass == null) cleanupExceptionClass = exceptionNameOnly(failure) }
                    try { instance.javaClass.getMethod("release").invoke(instance); released = true } catch (failure: Exception) { if (cleanupExceptionClass == null) cleanupExceptionClass = exceptionNameOnly(failure) }
                }
                returned = audio.abandonAudioFocusRequest(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                focusOwned.set(false)
            }
            events.close()
        }
        return Observation(result, phase, exceptionClass, cleanupExceptionClass, initializeConfirmed, listenerRegistered, started, stopCalled, removed, released, returned)
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
