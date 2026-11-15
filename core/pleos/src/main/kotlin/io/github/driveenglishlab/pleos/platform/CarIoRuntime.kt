package io.github.driveenglishlab.pleos.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** All bookkeeping belongs to main. A timeout never frees a running native slot. */
internal class CarIoRuntime(
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val now: () -> Long = SystemClock::elapsedRealtime,
    executors: List<Executor> = List(3) { lane ->
        Executors.newSingleThreadExecutor { task -> Thread(task, "car-public-io-$lane").apply { isDaemon = true } }
    },
    private val budgetMs: Long = 8_000,
) {
    enum class Channel { PROPERTY, UXR, POLICY }
    internal class Work(
        val owner: Any,
        val key: Any,
        val deadline: Long,
        val prepare: () -> (() -> Any?),
        val timeout: () -> Unit,
        val finish: (Any?, Throwable?, Boolean) -> Unit,
    ) {
        @Volatile var expired = false
        @Volatile var retired = false
        lateinit var timer: Runnable
    }
    private class Lane(val executor: Executor) {
        var active: Work? = null
        var pending: Work? = null
        var cleanup: Work? = null
    }
    private val lanes = executors.map(::Lane)
    private var session: Any? = null
    private var nextOwner: Any? = null
    private var nextStart: (() -> Unit)? = null

    fun acquire(owner: Any, start: () -> Unit): Boolean {
        checkMain()
        if (session == null) { session = owner; start(); return true }
        if (nextOwner == null) { nextOwner = owner; nextStart = start; return true }
        return false
    }
    fun cancelWaiting(owner: Any) {
        checkMain()
        if (nextOwner === owner) { nextOwner = null; nextStart = null }
    }
    fun release(owner: Any) {
        checkMain(); check(session === owner)
        check(lanes.all { it.active == null && it.pending == null && it.cleanup == null })
        session = null
        val next = nextOwner; val action = nextStart
        nextOwner = null; nextStart = null
        if (next != null && action != null) { session = next; action() }
    }

    /** Pending same-key refresh retains its original admission deadline and timeout. */
    fun submit(channel: Channel, owner: Any, key: Any, prepare: () -> (() -> Any?),
        timeout: () -> Unit, deadline: Long? = null, finish: (Any?, Throwable?, Boolean) -> Unit): Boolean {
        checkMain(); check(session === owner)
        val lane = lanes[channel.ordinal]
        if (lane.cleanup != null) return false
        val pending = lane.pending
        if (pending != null) {
            if (pending.owner === owner && pending.key == key) { return true }
            return false
        }
        val work = work(owner, key, prepare, timeout, finish, deadline)
        lane.pending = work
        pump(lane)
        return true
    }
    /** Retire intent is a separate bounded slot; a full normal queue cannot lose it. */
    fun retire(channel: Channel, owner: Any, prepare: () -> (() -> Any?),
        timeout: () -> Unit, finish: (Any?, Throwable?, Boolean) -> Unit) {
        checkMain(); check(session === owner)
        val lane = lanes[channel.ordinal]
        lane.pending?.let { main.removeCallbacks(it.timer) }; lane.pending = null
        lane.active?.takeIf { it.owner === owner }?.retired = true
        if (lane.cleanup == null) lane.cleanup = work(owner, "cleanup", prepare, timeout, finish)
        pump(lane)
    }
    fun idle(channel: Channel): Boolean = lanes[channel.ordinal].let { it.active == null && it.pending == null && it.cleanup == null }
    fun occupancy(): List<Pair<Int, Int>> = lanes.map { (if (it.active == null) 0 else 1) to (if (it.pending == null) 0 else 1) }
    private class Watch(val deadline: Long, val fire: () -> Unit) { lateinit var runnable: Runnable }
    private val watches = mutableSetOf<Watch>()
    fun admissionDeadline(): Long = now() + budgetMs
    fun expired(deadline: Long): Boolean = now() >= deadline
    fun connectionDeadline(deadline: Long, fire: () -> Unit): () -> Unit {
        checkMain()
        val watch = Watch(deadline, fire)
        watch.runnable = Runnable { if (watches.remove(watch)) fire() }
        watches += watch; main.postDelayed(watch.runnable, (deadline - now()).coerceAtLeast(0))
        return { checkMain(); watches.remove(watch); main.removeCallbacks(watch.runnable) }
    }
    /** Same deadline path used by the Handler; test clock advances without native sleeps. */
    internal fun expireDue() {
        checkMain()
        watches.toList().filter { now() >= it.deadline }.forEach { it.runnable.run() }
        lanes.flatMap { listOfNotNull(it.active, it.pending, it.cleanup) }.distinct()
            .filter { !it.expired && now() >= it.deadline }.forEach { it.timer.run() }
    }
    private fun work(owner: Any, key: Any, prepare: () -> (() -> Any?), timeout: () -> Unit,
        finish: (Any?, Throwable?, Boolean) -> Unit, deadline: Long? = null): Work {
        val work = Work(owner, key, minOf(now() + budgetMs, deadline ?: Long.MAX_VALUE), prepare, timeout, finish)
        work.timer = Runnable {
            if (!work.expired) { work.expired = true; timeout() }
            for (lane in lanes) if (lane.pending === work) lane.pending = null
            // Cleanup stays reserved even after its logical deadline.
        }
        main.postDelayed(work.timer, (work.deadline - now()).coerceAtLeast(0))
        return work
    }
    private fun pump(lane: Lane) {
        if (lane.active != null) return
        val work = lane.cleanup ?: lane.pending ?: return
        val cleanup = lane.cleanup === work
        if (cleanup) lane.cleanup = null else lane.pending = null
        if (!cleanup && (work.expired || now() >= work.deadline)) {
            main.removeCallbacks(work.timer)
            if (!work.expired) { work.expired = true; work.timeout() }
            pump(lane); return
        }
        // Freeze the immutable read lease on main immediately before native entry.
        val body = work.prepare()
        lane.active = work
        lane.executor.execute {
            var value: Any? = null; var failure: Throwable? = null
            try { if (cleanup || !work.retired && !work.expired && now() < work.deadline) value = body() } catch (error: Exception) { failure = error } catch (error: LinkageError) { failure = error }
            main.post {
                check(lane.active === work)
                main.removeCallbacks(work.timer)
                lane.active = null
                val expired = work.expired || now() >= work.deadline
                if (expired && !work.expired) { work.expired = true; work.timeout() }
                work.finish(value, failure, expired)
                pump(lane)
            }
        }
    }
    private fun checkMain() = check(Looper.myLooper() == main.looper)
    companion object {
        /** One pool/quota for all monitors, including reconnect and timed-out lifetimes. */
        val shared by lazy { CarIoRuntime() }
    }
}
