package io.github.ccch1mneyyy.dsh_app.runtime

import io.github.ccch1mneyyy.dsh_app.BuildConfig
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.R
import android.content.Context
import io.github.ccch1mneyyy.dsh_app.performance.PerformancePolicy
import android.content.Intent
import android.net.LinkProperties
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import org.json.JSONArray
import org.json.JSONObject
import io.github.ccch1mneyyy.dsh_app.phone.skills.PhoneSkillRoute
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal fun acceptsRunReady(
    sourceIsCurrent: Boolean,
    phase: RuntimeController.Phase,
    runGeneration: Int,
    stopGeneration: Int,
    resetting: Boolean,
): Boolean =
    sourceIsCurrent && phase != RuntimeController.Phase.STOPPING && stopGeneration < runGeneration && !resetting

/**
 * Process-wide owner of the local runtime: install state, the DSH process, logs, and the
 * failure detection / safe mode around it.
 * The Flutter plugin and the foreground service both talk to this singleton.
 */
class RuntimeController private constructor(private val app: Context) : DshProcess.Listener {

    enum class Phase(val wire: String) {
        NOT_INSTALLED("notInstalled"), INSTALLING("installing"), INSTALLED("installed"),
        STARTING("starting"), RUNNING("running"), STOPPING("stopping"), ERROR("error"),
    }

    fun interface EventSink {
        fun emit(event: Map<String, Any?>)
    }

    internal class StopRequest(
        val runGeneration: Int,
        val startTicketOwner: Long?,
        private val onDone: (Throwable?) -> Unit,
    ) {
        private val completed = java.util.concurrent.atomic.AtomicBoolean()
        fun belongsTo(generation: Int): Boolean = runGeneration == generation
        fun belongsToStart(ticket: Long): Boolean = startTicketOwner == ticket
        fun finish(error: Throwable?) {
            if (completed.compareAndSet(false, true)) onDone(error)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-runtime") }
    private val stopRequestIds = AtomicLong()
    private val stopRequests = ConcurrentHashMap<Long, StopRequest>()
    private val sinks = LinkedHashSet<EventSink>()
    private val statusPublishLock = Any()
    private val logDir = File(app.filesDir, "runtime/logs")
    private val runtimeLogs = RuntimeLogs(
        File(logDir, "dsh.log"), SystemClock::elapsedRealtime,
        post = { action -> main.post { action() } },
        deliver = { entries ->
            val event = mapOf("type" to "logs", "entries" to entries.map { it.toMap() })
            val targets = synchronized(sinks) { sinks.toList() }
            targets.forEach { it.emit(event) }
        },
    )
    private val lkgFile = File(app.filesDir, "runtime/lkg/lkg.json")
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val workspaceRecovery = WorkspaceRecoveryMode(prefs.getBoolean(K_WORKSPACE_RECOVERY, false))

    val manifest: RuntimeManifest by lazy { RuntimeManifest.load(app) }
    private val installer by lazy { RuntimeInstaller(app, manifest, ::note, rootRuntime = BuildConfig.ROOT_RUNTIME) }
    @Volatile private var process: DshProcess? = null
    @Volatile private var lastLaunch: LaunchDiagnostics? = null
    /** The start in progress is turning PRoot's fake hard links into real files (see [L2sMigration]). */
    @Volatile private var tidyingFiles = false

    @Volatile var phase: Phase = Phase.NOT_INSTALLED
        private set
    @Volatile private var baseUrl: String? = null
    @Volatile private var token: String? = null
    @Volatile private var error: String? = null
    @Volatile private var pendingEnv: Map<String, String> = emptyMap()
    @Volatile private var pendingPhoneStorage = false
    internal val rootStartupGate = RootStartupGate()
    private class PendingInstall(
        val ticket: RootStartupGate.Ticket,
        val dropKept: Boolean,
        val unpack: Boolean,
        private val onDone: (Throwable?) -> Unit,
    ) {
        private val finished = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(error: Throwable?) { if (finished.compareAndSet(false, true)) onDone(error) }
    }
    private var pendingInstall: PendingInstall? = null
    /** Whether the managed AGENTS.md tells the model about ```dsh-ui views (settings, on by default). */
    @Volatile private var pendingVisualReplies = true

    // Safe mode and failure detection. State that must survive the app being killed lives in [prefs].
    @Volatile private var safeState = SafeState.parse(prefs.getString(K_SAFE_STATE, null))
    @Volatile private var safeReason: String? = prefs.getString(K_SAFE_REASON, null)
    @Volatile private var strikes = prefs.getInt(K_STRIKES, 0)
    /** Latest unexpected exit; the failure card shows it until a later run gets going. */
    @Volatile private var failure: RunFailure? = readFailure(K_FAILURE)

    /** Latest failure of the main profile (normal mode): what safe mode is there to fix. */
    @Volatile private var mainFailure: RunFailure? = readFailure(K_MAIN_FAILURE)

    /** Mode of the current (or last) launched process. */
    @Volatile private var runSafe = false
    @Volatile private var runGen = 0
    @Volatile private var activeStartTicket: Long? = null
    @Volatile private var stopGen = -1
    @Volatile private var resetting = false
    @Volatile private var pendingRootCleanup: DshProcess? = null
    // A phone draft save may stage only within the ready runtime captured by its button press.
    // Independent of bundled-skill attachment grants; it never borrows their write permission.
    @Volatile private var phoneSkillStoppedGeneration = -1
    // Retire skill writes at the stop request, before the Service receives ACTION_STOP.
    // This gate does not change process lifecycle or A5's ownership/launch decisions.
    private val managedSkillGate = ManagedSkillRuntimeGate()
    private val managedSkills = ManagedSkillQueue(
        execute = { action -> worker.execute { action() } },
        deliver = { action ->
            if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
        },
    )
    private val autoRestarts = ArrayDeque<Long>()
    @Volatile private var readyAt: Long? = null
    @Volatile private var healthyAt: Long? = null

    /** The main profile as it was when the current normal run booted; becomes the LKG when healthy. */
    @Volatile private var bootProfile: ProfileSnapshot? = null
    private val runTail = ArrayDeque<String>()
    private val networkAccess = NetworkAccess()
    private val dnsLog = DnsConfigLog()

    /** Start → spawn → listening, logged in one line when DSH comes up. */
    private val timeline = StartupTimeline.forThisProcess()

    /** The process being started or running is a safe-mode one. */
    val safeRun: Boolean get() = runSafe
    internal val runtimeGeneration: Int get() = runGen

    init {
        phase = try {
            if (installer.isCurrent()) Phase.INSTALLED else Phase.NOT_INSTALLED
        } catch (e: Exception) {
            error = "manifest: ${e.message}"
            Phase.ERROR
        }
    }

    fun addSink(sink: EventSink) = synchronized(sinks) { sinks += sink }
    fun removeSink(sink: EventSink) = synchronized(sinks) { sinks -= sink }

    fun status(): Map<String, Any?> = mapOf(
        "abi" to (runCatching { manifest.abi }.getOrNull() ?: Build.SUPPORTED_ABIS.firstOrNull()),
        "bundledVersion" to runCatching { manifest.runtimeVersion }.getOrNull(),
        "installedVersion" to runCatching { installer.installedVersion() }.getOrNull(),
        "dshVersion" to runCatching { manifest.dshVersion }.getOrNull(),
        "nodeVersion" to runCatching { manifest.nodeVersion }.getOrNull(),
        "phase" to phase.wire,
        "baseUrl" to baseUrl,
        "token" to token,
        "managedSkillGeneration" to managedSkillGate.generationFor(token),
        "error" to error,
        "mode" to if (currentModeSafe()) "safe" else "normal",
        "safeState" to safeState.wire,
        "safeReason" to safeReason,
        "strikes" to strikes,
        "autoSafe" to autoSafe(),
        "workspaceRecovery" to workspaceRecovery.enabled,
        "failure" to failure?.toMap(),
        "mainFailure" to mainFailure?.toMap(),
        "healthyAt" to healthyAt,
        "networkBlocked" to networkAccess.isBlocked,
        "networkBlockedAt" to networkAccess.blockedAt,
        "directEngineEnabled" to directEngine().enabled,
        "directEngineAvailable" to directEngineAvailable(),
        "directEngineActive" to (process?.executionMode == "direct"),
        "directEngineFallbackReason" to lastLaunch?.fallbackReason,
        "currentExecutionMode" to process?.executionMode,
        "launchSelectedMode" to lastLaunch?.selectedMode,
        "launchSpawned" to (lastLaunch?.spawned == true),
        "launchFailureReason" to lastLaunch?.failureReason,
        "tidyingFiles" to tidyingFiles,
        "rootRuntime" to BuildConfig.ROOT_RUNTIME,
    )

    private fun directEngine() = DirectLaunch.Preference(
        saved = if (prefs.contains(K_DIRECT_ENGINE)) prefs.getBoolean(K_DIRECT_ENGINE, false) else null,
        available = directEngineAvailable(),
        failedRuntime = prefs.getString(K_DIRECT_FAILED, null),
        runtime = runCatching { manifest.runtimeVersion }.getOrNull(),
    )
    // Root builds launch through the root backend only: Direct never applies, and with it the
    // launch-failure fallback back to PRoot is unreachable (SafePolicy.directLaunchFailed).
    private fun directEngineAvailable(): Boolean = !BuildConfig.ROOT_RUNTIME && runCatching {
        manifest.raw.optJSONObject("directEngine")?.optInt("schema") == 2
    }.getOrDefault(false)

    /**
     * An App-local option, on by default; a running process never changes its execution world.
     * Any explicit choice also forgets an earlier failed Direct launch, so turning it on tries again.
     */
    @Synchronized fun setDirectEngine(enabled: Boolean) {
        check(process == null && phase !in setOf(Phase.STARTING, Phase.RUNNING, Phase.STOPPING, Phase.INSTALLING) && !resetting) { "stop the engine before changing its launch method" }
        check(!enabled || directEngineAvailable()) { "this bundled runtime does not support direct launch" }
        prefs.edit().putBoolean(K_DIRECT_ENGINE, enabled).remove(K_DIRECT_FAILED).apply()
        emitStatus()
    }

    /** Selects a separate workspace index for the next launch; never opens either index here. */
    fun setWorkspaceRecovery(enabled: Boolean, onDone: (Throwable?) -> Unit) {
        worker.execute {
            try {
                try {
                    checkRecoveryWrite(mainProfileOnly = false)
                } catch (e: Exception) {
                    // A failed observation is not proof that the old writer has stopped.
                    throw if (e is RuntimeNotStoppedException) e
                    else RuntimeNotStoppedException().apply { initCause(e) }
                }
                workspaceRecovery.set(enabled) { next ->
                    prefs.edit().putBoolean(K_WORKSPACE_RECOVERY, next).commit()
                }
                emitStatus()
                main.post { onDone(null) }
            } catch (e: Throwable) {
                main.post { onDone(e) }
            }
        }
    }

    /** While a process is up: its mode; otherwise the mode the next start will use. */
    private fun currentModeSafe(): Boolean {
        val active = process != null || phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.STOPPING
        return if (active) runSafe else safeState == SafeState.ON
    }

    /** [dropKept]: give up what a reset kept but cannot be put back (see [KeepFailed]). */
    @Synchronized fun install(dropKept: Boolean = false, onDone: (Throwable?) -> Unit) {
        if (!installAllowed(phase, resetting, process != null)) {
            onDone(RuntimeUpgradeBlocked("running"))
            return
        }
        if (installer.isCurrent() && phase != Phase.ERROR) {
            if (phase == Phase.NOT_INSTALLED) setPhase(Phase.INSTALLED)
            onDone(null)
            return
        }
        val upgrade = installer.installedVersion() != null
        val ticket = rootStartupGate.begin(RootStartupGate.Kind.INSTALL)
        val request = PendingInstall(ticket, dropKept, true, onDone)
        pendingInstall = request
        setPhase(Phase.INSTALLING)
        prepareRootRun(ticket) { accepted ->
            if (!accepted) {
                if (pendingInstall?.ticket == ticket) pendingInstall = null
                if (rootStartupGate.current(ticket)) {
                    error = "root unavailable"
                    setPhase(Phase.ERROR)
                }
                request.finish(IllegalStateException(if (rootStartupGate.current(ticket)) "root unavailable" else "cancelled"))
                return@prepareRootRun
            }
            runCatching {
                rootStartupGate.dispatch(ticket) {
                    val intent = Intent(app, DshService::class.java).setAction(DshService.ACTION_INSTALL)
                        .putExtra("upgrade", upgrade).putExtra(DshService.EXTRA_TICKET, ticket.id)
                    sendForegroundService(intent)
                }
            }.onFailure { failure ->
                if (pendingInstall?.ticket == ticket) pendingInstall = null
                error = "install failed: ${failure.message}"
                setPhase(Phase.ERROR)
                rootStartupGate.invalidate()
                request.finish(failure)
            }
        }
    }

    internal fun acceptService(ticket: Long, install: Boolean): Boolean {
        if (!rootStartupGate.consume(ticket, if (install) RootStartupGate.Kind.INSTALL else RootStartupGate.Kind.START)) return false
        if (BuildConfig.ROOT_RUNTIME && !io.github.ccch1mneyyy.dsh_app.notify.NotifyPostGate.ready(app)) {
            error = "notification permission unavailable"
            setPhase(Phase.ERROR)
            val request = pendingInstall?.takeIf { it.ticket.id == ticket }
            if (request != null) pendingInstall = null
            request?.finish(IllegalStateException(error))
            return false
        }
        return true
    }

    private fun cancelPendingInstall() {
        val request = pendingInstall ?: return
        pendingInstall = null
        request.finish(IllegalStateException("cancelled"))
    }

    /** Called only after the matching service has entered foreground and acquired its wake lock. */
    internal fun installServiceReady(id: Long) {
        val request = pendingInstall?.takeIf { it.ticket.id == id } ?: return
        pendingInstall = null
        startInstall(request)
    }

    private fun startInstall(request: PendingInstall) {
        worker.execute {
            var installError: Throwable? = null
            try {
                check(rootStartupGate.current(request.ticket)) { "cancelled" }
                if (request.unpack) {
                    log("[dsh-app] unpacking runtime ${manifest.runtimeVersion} (${manifest.abi})", "system")
                    val started = System.currentTimeMillis()
                    check(retryPendingRootCleanup()) { "previous root cleanup is unconfirmed; retry install after cleanup" }
                    if (!BuildConfig.ROOT_RUNTIME && installer.rootfs.exists() &&
                        !DshProcess(app, manifest, installer.rootfs, this).killStrays()) {
                        throw RuntimeUpgradeBlocked("running")
                    }
                    installer.install(request.dropKept) { done, total ->
                        check(rootStartupGate.current(request.ticket)) { "cancelled" }
                        emit(mapOf("type" to "progress", "step" to "extract", "done" to done, "total" to total))
                    }
                    log("[dsh-app] runtime ready in ${(System.currentTimeMillis() - started) / 1000}s", "system")
                }
                if (rootStartupGate.current(request.ticket)) {
                    error = null
                    strikes = 0
                    failure = null
                    prefs.edit().putInt(K_STRIKES, 0).remove(K_FAILURE).apply()
                    setPhase(Phase.INSTALLED)
                }
            } catch (e: Throwable) {
                installError = e
                if (rootStartupGate.current(request.ticket)) {
                    error = "install failed: ${e.message}"
                    log("[dsh-app] $error", "system")
                    setPhase(Phase.ERROR)
                }
            }
            val outcome = installError
            main.post {
                // This request reached foreground; only its service may finish its notification.
                app.startService(Intent(app, DshService::class.java).setAction(DshService.ACTION_INSTALL_DONE)
                    .putExtra(DshService.EXTRA_TICKET, request.ticket.id)
                    .putExtra(DshService.EXTRA_INSTALL_OK, outcome == null))
                request.finish(outcome)
            }
        }
    }

    fun start(
        env: Map<String, String>,
        phoneStorage: Boolean = false,
        mode: String? = null,
        visualReplies: Boolean = pendingVisualReplies,
    ): Long? {
        pendingPhoneStorage = phoneStorage
        pendingVisualReplies = visualReplies
        if (phase in setOf(Phase.RUNNING, Phase.STARTING, Phase.INSTALLING, Phase.STOPPING) || resetting) return null
        pendingEnv = env
        val next = SafePolicy.onStartRequest(safeState, mode)
        if (next != safeState) setSafeState(next, if (next == SafeState.ON) SafePolicy.REASON_MANUAL else safeReason)
        val ticket = rootStartupGate.begin(RootStartupGate.Kind.START)
        activeStartTicket = ticket.id
        setPhase(Phase.STARTING)
        prepareRootRun(ticket) { accepted ->
            if (!accepted) {
                if (rootStartupGate.current(ticket)) {
                    error = "root unavailable"
                    setPhase(Phase.ERROR)
                }
                return@prepareRootRun
            }
            runCatching {
                rootStartupGate.dispatch(ticket) {
                    sendForegroundService(Intent(app, DshService::class.java).setAction(DshService.ACTION_START)
                        .putExtra(DshService.EXTRA_TICKET, ticket.id))
                }
            }.onFailure {
                error = "start failed: ${it.message}"
                setPhase(Phase.ERROR)
            }
        }
        return ticket.id
    }

    private fun sendForegroundService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
    }

    private fun prepareRootRun(ticket: RootStartupGate.Ticket, ready: (Boolean) -> Unit) {
        if (!BuildConfig.ROOT_RUNTIME) {
            rootStartupGate.prepare(ticket, { it() }, { it() }, { true }, ready)
            return
        }
        rootStartupGate.prepare(ticket,
            execute = { action -> Thread({ action() }, "dsh-root-startup").start() },
            deliver = { action -> main.post { action() } },
            preparation = { current ->
                runCatching { PhoneControl.get(app).background.prepareRootAccessForStartup(current) }
                    .getOrNull()
                    ?.configured == true
            },
            ready = ready,
        )
    }

    fun ensureRootAccess(onDone: (code: String?, reason: String?, missing: List<String>) -> Unit) {
        PhoneControl.get(app).background.ensureRootAccessForStartup { code, reason, missing ->
            main.post { onDone(code, reason, missing) }
        }
    }

    /**
     * Writes the app's diagnostics snapshot where the engine's agent can read it
     * (`$DSH_HOME/dsh-app/diagnostics.md`). A few KB on the platform thread, after the
     * engine came up or when the person asks from 设置 → 诊断. Null when the runtime is
     * not installed or the write failed.
     */
    fun publishDiagnostics(markdown: String, manual: Boolean = false): Map<String, Any?>? = try {
        if (!installer.isCurrent()) null
        else {
            val file = GuestSetup.writeDiagnostics(installer.rootfs, markdown, safe = currentModeSafe(), manual = manual)
            mapOf("path" to file.absolutePath, "bytes" to file.length())
        }
    } catch (e: Exception) {
        log("[dsh-app] could not write the diagnostics snapshot: ${e.message}", "system")
        null
    }

    fun stop(onDone: (Throwable?) -> Unit) {
        requestStop(onDone, startTicketOwner = null)
    }

    fun stopOwnedStart(ticketId: Long, onDone: (Throwable?) -> Unit) {
        val ticket = RootStartupGate.Ticket(ticketId, RootStartupGate.Kind.START)
        if (activeStartTicket != ticketId || !rootStartupGate.invalidate(ticket)) {
            onDone(IllegalStateException("start request is no longer current"))
            return
        }
        requestStop(onDone, startTicketOwner = ticketId, gateInvalidated = true)
    }

    private fun requestStop(
        onDone: (Throwable?) -> Unit,
        startTicketOwner: Long?,
        gateInvalidated: Boolean = false,
    ) {
        val requestId = stopRequestIds.incrementAndGet()
        val generation = runGen
        stopRequests[requestId] = StopRequest(generation, startTicketOwner, onDone)
        try {
            synchronized(this) { phoneSkillStoppedGeneration = maxOf(phoneSkillStoppedGeneration, generation) }
            stopGen = generation
            managedSkillGate.stop(generation)
            if (!gateInvalidated) rootStartupGate.invalidate()
            if (phase == Phase.INSTALLING) {
                setPhase(if (installer.isCurrent()) Phase.INSTALLED else Phase.NOT_INSTALLED)
            }
            cancelPendingInstall()
            Thread({ PhoneControl.get(app).background.cancelUnwantedPending() }, "dsh-root-cancel").start()
            val intent = Intent(app, DshService::class.java).setAction(DshService.ACTION_STOP)
                .putExtra(DshService.EXTRA_STOP_REQUEST, requestId)
                .putExtra(DshService.EXTRA_STOP_GENERATION, generation)
            if (app.startService(intent) == null) {
                finishStopRequest(requestId, IllegalStateException("runtime service did not accept stop"))
            }
        } catch (error: Throwable) {
            finishStopRequest(requestId, error)
        }
    }

    private fun finishStopRequest(requestId: Long, error: Throwable?) {
        if (requestId == 0L) return
        stopRequests.remove(requestId)?.finish(error)
    }

    /** Private bundled-skill transaction; never falls back to a different executor/root. */
    internal fun installManagedSkill(owner: ManagedSkillOwner, raw: Any?, onDone: (Map<String, Any?>) -> Unit) {
        val request = ManagedSkillRequest.parse(raw)
        val run = request?.let { managedSkillGate.capture(owner, it.expectedToken, it.expectedGeneration) }
        if (request == null || run == null) {
            onDone(mapOf("result" to null, "current" to false, "reason" to if (request == null) "invalid" else "stale"))
            return
        }
        managedSkills.submit(owner, request, installer.rootfs, current = {
            managedSkillGate.current(run) && phase == Phase.RUNNING && runGen == run.runtimeGeneration &&
                stopGen < run.runtimeGeneration && !resetting && token == run.token
        }, onDone = onDone)
    }

    internal fun cancelManagedSkill(owner: ManagedSkillOwner, requestId: String? = null) {
        managedSkills.cancel(owner, requestId)
    }

    /** Original Runtime/root captured at the trusted local button request, never after queueing.
     * This is a read origin, not an invocation or permission to execute a phone action. */
    internal class PhoneSkillOrigin internal constructor(
        internal val runtime: RuntimeController,
        internal val generation: Int,
        internal val root: File,
    )

    @Synchronized internal fun capturePhoneSkillOrigin(): PhoneSkillOrigin? =
        if (phase == Phase.RUNNING && !runSafe && !resetting && stopGen < runGen &&
            phoneSkillStoppedGeneration < runGen) PhoneSkillOrigin(this, runGen, installer.rootfs) else null

    // Read-only generation fence for physical action gates; never acquires the Runtime monitor.
    // The final generation read rejects mixed fields across an unexpected-exit/restart.
    internal fun phoneSkillOriginCurrent(origin: PhoneSkillOrigin): Boolean =
        origin.runtime === this && phase == Phase.RUNNING && !runSafe && !resetting &&
            runGen == origin.generation && stopGen < origin.generation &&
            phoneSkillStoppedGeneration < origin.generation && runGen == origin.generation

    /** Uses the existing rootfs worker. The only descriptor input is the native reviewed table;
     * private UI/JSON never supplies it. An empty table has no executable App registration.
     * Caller keeps this same origin for pending/claim checks, outside registry/Tasks locks. */
    internal fun readPhoneSkillBundle(origin: PhoneSkillOrigin, name: String, revision: String?,
        descriptors: Map<String, PhoneSkillRoute.Descriptor>,
        onDone: (PhoneSkillBundleReader.Read) -> Unit) {
        val reviewed = java.util.Collections.unmodifiableMap(LinkedHashMap(descriptors))
        worker.execute {
            val result = if (!phoneSkillOriginCurrent(origin)) {
                PhoneSkillBundleReader.Read.Rejected(PhoneSkillBundleReader.Reason.UNAVAILABLE)
            } else PhoneSkillBundleReader.read(origin.root, name, revision, reviewed)
            main.post {
                onDone(if (phoneSkillOriginCurrent(origin)) result
                    else PhoneSkillBundleReader.Read.Rejected(PhoneSkillBundleReader.Reason.UNAVAILABLE))
            }
        }
    }

    /** User-selected phone draft only. Lock order: Controller -> write; no IO under either. */
    internal fun savePhoneSkill(write: PhoneSkillWrite, onDone: (PhoneSkillInstallResult) -> Unit) {
        val origin = capturePhoneSkillOrigin()?.let(write::bindSaveOrigin)
        if (origin == null) {
            val result = PhoneSkillInstallResult.Refused.STALE
            write.finish(result)
            main.post { onDone(result) }
            return
        }
        try { worker.execute {
            fun current(): Boolean = phoneSkillOriginCurrent(origin)
            var result: PhoneSkillInstallResult? = null
            try {
                val bundle = write.bundle
                result = if (bundle == null) PhoneSkillInstaller.install(origin.root, write.document,
                    canStage = { synchronized(this) { current() && write.canStage() } },
                    beginCommit = { synchronized(this) { current() && write.beginCommit() } },
                ) else when (val saved = PhoneSkillBundleInstaller.install(origin.root, bundle,
                    canStage = { synchronized(this) { current() && write.canStage() } },
                    beginCommit = { synchronized(this) { current() && write.beginCommit() } },
                )) {
                    is PhoneSkillBundleInstaller.Result.Saved -> PhoneSkillInstallResult.BundleSaved(saved.identity)
                    is PhoneSkillBundleInstaller.Result.Unverified -> PhoneSkillInstallResult.BundleUnverified(
                        saved.identity, saved.publication, saved.code)
                    PhoneSkillBundleInstaller.Result.Refused.STALE -> PhoneSkillInstallResult.Refused.STALE
                    PhoneSkillBundleInstaller.Result.Refused.NO_HOME -> PhoneSkillInstallResult.Refused.NO_HOME
                    PhoneSkillBundleInstaller.Result.Refused.CONFLICT -> PhoneSkillInstallResult.Refused.CONFLICT
                    PhoneSkillBundleInstaller.Result.Refused.IO, PhoneSkillBundleInstaller.Result.Refused.INVALID -> PhoneSkillInstallResult.Refused.IO
                }
            } finally {
                // Physical completion precedes any UI callback. A permit already consumed is
                // not proof of zero publication when the operation fails without a result.
                val finished = result ?: write.bundle?.takeIf { write.phase() == PhoneSkillWrite.Phase.COMMITTING }?.let {
                    PhoneSkillInstallResult.BundleUnverified(it.identity, PhoneSkillBundleInstaller.Publication.UNKNOWN,
                        PhoneSkillBundleInstaller.Verification.VERIFICATION_UNAVAILABLE)
                } ?: PhoneSkillInstallResult.Refused.IO
                write.finish(finished)
                main.post { onDone(finished) }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Submission failed: this job cannot enter the reader/installer.
            val result = PhoneSkillInstallResult.Refused.IO
            write.finish(result)
            main.post { onDone(result) }
        }
    }

    /** Original worker/root only. Completion runs in the physical job's finally, NOT on main.
     * The caller clears its Receipt pin there before separately delivering UI callbacks. */
    internal fun checkPhoneSkillSave(binding: PhoneSkillWrite.SaveBinding,
        publication: PhoneSkillBundleInstaller.Publication, onDone: (PhoneSkillInstallResult) -> Unit) {
        fun unverified(code: PhoneSkillBundleInstaller.Verification) =
            PhoneSkillInstallResult.BundleUnverified(binding.identity, publication, code)
        try { worker.execute {
            var result: PhoneSkillInstallResult = unverified(PhoneSkillBundleInstaller.Verification.VERIFICATION_UNAVAILABLE)
            try {
                val origin = binding.origin
                result = if (origin == null || !phoneSkillOriginCurrent(origin)) {
                    unverified(PhoneSkillBundleInstaller.Verification.ORIGIN_UNAVAILABLE)
                } else {
                    val identity = binding.identity
                    val read = PhoneSkillBundleReader.read(origin.root, identity.name, identity.revision, binding.descriptors)
                    when {
                        !phoneSkillOriginCurrent(origin) -> unverified(PhoneSkillBundleInstaller.Verification.ORIGIN_UNAVAILABLE)
                        read is PhoneSkillBundleReader.Read.Accepted && PhoneSkillBundleInstaller.matches(identity, read) ->
                            PhoneSkillInstallResult.BundleSaved(identity)
                        read is PhoneSkillBundleReader.Read.Rejected && read.reason == PhoneSkillBundleReader.Reason.IO ->
                            unverified(PhoneSkillBundleInstaller.Verification.VERIFICATION_UNAVAILABLE)
                        else -> unverified(PhoneSkillBundleInstaller.Verification.BUNDLE_INVALID)
                    }
                }
            } finally { onDone(result) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            onDone(unverified(PhoneSkillBundleInstaller.Verification.ORIGIN_UNAVAILABLE))
        }
    }

    internal fun attachManagedSkillOwner(): ManagedSkillOwner {
        val owner = ManagedSkillOwner()
        // Gate attachment and the last unretired real ready share a short lock.
        // Do not replay a phase/token observation across onReady or retirement.
        managedSkillGate.attach(owner)?.let { managedSkills.cancel(it) }
        return owner
    }

    internal fun detachManagedSkillOwner(owner: ManagedSkillOwner) {
        managedSkillGate.detach(owner)
        managedSkills.cancel(owner)
    }

    @Synchronized internal fun startProcess() {
        if (process != null || phase == Phase.INSTALLING || phase == Phase.STOPPING) return
        pendingRootCleanup?.let { pending ->
            if (pending.rootCleanupConfirmed) {
                if (pendingRootCleanup === pending) pendingRootCleanup = null
            } else {
                error = "previous root container cleanup is unconfirmed; stop or reset to retry cleanup"
                log("[dsh-app] $error", "system")
                setPhase(Phase.ERROR)
                DshService.stopSelfIfRunning(app, runGen)
                return
            }
        }
        lastLaunch = null
        if (!installer.isCurrent()) {
            error = "runtime not installed"
            setPhase(Phase.ERROR)
            DshService.stopSelfIfRunning(app, runGen)
            return
        }
        baseUrl = null
        token = null
        error = null
        val safe = safeState == SafeState.ON
        runSafe = safe
        val gen = ++runGen
        readyAt = null
        healthyAt = null
        synchronized(runTail) { runTail.clear() }
        setPhase(Phase.STARTING)
        timeline.requested()
        worker.execute {
            var launched: DshProcess? = null
            try {
                noteDns(GuestSetup.writeResolvConf(app, installer.rootfs), DnsConfigSource.START)
                val storage = pendingPhoneStorage && PhoneStorage.isGranted(app) && PhoneStorage.binds().isNotEmpty()
                if (!PhoneStorage.syncShortcut(installer.rootfs, storage)) {
                    log("[dsh-app] could not ${if (storage) "create" else "remove"} the phone storage shortcut in the guest home; the directory may be root-owned", "system")
                }
                // Root installs give /opt to the archive identity (0755), so the app
                // can no longer create its own overlay directory; prepare exactly that
                // one directory (phone / session-search / sign-in / voice / suenv all
                // live under it). Plain runtimes are untouched: app owns everything.
                if (BuildConfig.ROOT_RUNTIME &&
                    !withProfileFiles {
                        it.ensureAppOwnedDir(File(installer.rootfs, "opt/dsh-app"), appUid().toLong(), appGid().toLong())
                    }
                ) {
                    log("[dsh-app] could not prepare the guest overlay directory; app overlays may be unavailable", "system")
                }
                // Phone control: bridge up and guest files written before DSH starts; off in safe mode.
                val phone = PhoneControl.get(app).prepareLaunch(installer.rootfs, safe) { log(it, "system") }
                val searchOverlay = !safe && SessionSearchGuest.writeFiles(installer.rootfs)
                if (!safe && !searchOverlay) {
                    log("[dsh-app] message search overlay not written; searching matches titles only", "system")
                }
                val authOverlay = !safe && AppAuthGuest.writeFiles(app, installer.rootfs)
                if (!safe && !authOverlay) {
                    log("[dsh-app] subscription sign-in overlay not written; ChatGPT / Claude sign-in is unavailable", "system")
                }
                val speechOverlay = !safe && SpeechGuest.writeFiles(installer.rootfs)
                if (!safe && !speechOverlay) {
                    log("[dsh-app] voice input overlay not written; the voice model keeps DSH's defaults", "system")
                }
                if (BuildConfig.ROOT_RUNTIME && !safe && !SuEnvGuest.writeFiles(app, installer.rootfs)) {
                    log("[dsh-app] local environment briefing overlay not written; the engine keeps its base instructions", "system")
                }
                if (pendingPhoneStorage && !storage) log("[dsh-app] phone storage requested but permission not granted", "system")
                val env = LinkedHashMap<String, String>()
                env["TZ"] = java.util.TimeZone.getDefault().id
                env.putAll(pendingEnv)
                workspaceRecovery.applyEnvironment(env)
                PerformancePolicy.applyEngineEnvironment(app, env, manifest.launchArgv, manifest.launchEnv)
                if (safe) {
                    SafeMode.prepare(installer.rootfs, briefing())
                    env.putAll(SafeMode.GUEST_ENV)
                    bootProfile = null
                    log("[dsh-app] safe mode: booting profile ${SafeMode.PROFILE} rebuilt from the web template, plugins off", "system")
                } else {
                    bootProfile = readLkgSnapshot()
                }
                // startProcess advances runGen under this same monitor. Publish only while
                // this queued launch still owns that generation; once published, its non-null
                // process prevents another start from advancing the generation underneath it.
                val p = synchronized(this@RuntimeController) {
                    if (stopGen >= gen || gen != runGen) null
                    else DshProcess(
                        app, manifest, installer.rootfs,
                        object : DshProcess.Listener by this@RuntimeController {
                            override fun onLog(line: String, stream: String) {
                                if (stream != "system") {
                                    synchronized(runTail) {
                                        if (gen == runGen) {
                                            runTail.addLast(line)
                                            while (runTail.size > CrashClassifier.TAIL_LINES) runTail.removeFirst()
                                        }
                                    }
                                }
                                this@RuntimeController.onLog(line, stream)
                            }
                        },
                    ).also {
                        launched = it
                        lastLaunch = it.launchDiagnostics
                        process = it
                    }
                } ?: run {
                    PhoneControl.get(app).onEngineStopped()
                    return@execute
                }
                timeline.spawned()
                val direct = directEngine()
                p.start(env, if (storage) PhoneStorage.binds() else emptyList(), safe, phone, direct.requested, direct.failed, searchOverlay, authOverlay, speechOverlay) { actualDirect ->
                    // SafeMode.prepare writes its own briefing; leave the original home untouched.
                    if (!safe && !GuestSetup.writeAgentsMd(installer.rootfs, storage, phone, pendingVisualReplies, actualDirect, BuildConfig.ROOT_RUNTIME)) {
                        log("[dsh-app] could not write the guest AGENTS.md; the engine keeps its previous instructions", "system")
                    }
                }
                if (phone) PhoneControl.get(app).launched(p.phoneMounted)
            } catch (e: Throwable) {
                // Keep ownership while the old UID sweep runs, including a
                // failure after spawning but before the waiter was allocated.
                launched?.launchDiagnostics?.failed("startFailed")
                launched?.let { p ->
                    p.failStart(e.message ?: e.javaClass.simpleName)
                    val exited = p.awaitExit()
                    if (!exited || !p.rootCleanupConfirmed) retainPendingRootCleanup(p)
                }
                synchronized(this) {
                    if (gen != runGen) return@execute
                    process = null
                    PhoneControl.get(app).onEngineStopped()
                    val startupError = launched?.startupError
                    if (startupError == null && stopGen >= gen) {
                        // A cancelled preparation can throw before a waiter exists.
                        if (!resetting && phase == Phase.STOPPING) setPhase(Phase.INSTALLED)
                        return@execute
                    }
                    publishStartupFailure(startupError ?: e.message ?: e.javaClass.simpleName, ownerReleased = true)
                    log("[dsh-app] $error", "system")
                    if (!resetting) DshService.stopSelfIfRunning(app, gen)
                }
            }
        }
    }

    @Synchronized internal fun stopProcess(requestId: Long = 0L, expectedGeneration: Int? = null): Boolean {
        if (requestId != 0L) {
            val request = stopRequests[requestId] ?: return false
            if (!request.belongsTo(runGen)) {
                finishStopRequest(requestId, IllegalStateException("stop request belongs to an earlier runtime"))
                return false
            }
            val owner = request.startTicketOwner
            if (owner != null && !request.belongsToStart(activeStartTicket ?: 0L)) {
                finishStopRequest(requestId, IllegalStateException("start request is no longer current"))
                return false
            }
        }
        if (expectedGeneration != null && expectedGeneration != runGen) {
            if (requestId != 0L) finishStopRequest(requestId, IllegalStateException("stop request belongs to an earlier runtime"))
            return false
        }
        if (requestId == 0L || stopRequests[requestId]?.startTicketOwner == null) {
            rootStartupGate.invalidate()
        }
        if (phase == Phase.INSTALLING) {
            finishStopRequest(requestId, null)
            return false
        }
        stopGen = runGen
        val p = process
        if (p == null) {
            val pending = pendingRootCleanup
            if (pending != null) {
                worker.execute {
                    pending.stop()
                    val stopped = runCatching { pending.awaitExit(15_000) && pending.rootCleanupConfirmed }
                    if (stopped.getOrDefault(false)) {
                        clearPendingRootCleanup(pending)
                        finishStopRequest(requestId, null)
                    } else {
                        log("[dsh-app] previous root cleanup remains unconfirmed; retry stop or reset", "system")
                        finishStopRequest(requestId, stopped.exceptionOrNull() ?: IllegalStateException("runtime stop or root cleanup is unconfirmed"))
                    }
                }
                return true
            }
            if (phase == Phase.STARTING) {
                // The launch is still queued on the worker: finish the stop behind it.
                setPhase(Phase.STOPPING)
                worker.execute {
                    val launched = process
                    if (launched != null) {
                        launched.stop()
                        finishStopRequest(requestId, awaitStopResult(launched))
                    } else {
                        if (!resetting && phase == Phase.STOPPING) setPhase(Phase.INSTALLED)
                        finishStopRequest(requestId, null)
                    }
                }
            } else if (!resetting && phase != Phase.NOT_INSTALLED && phase != Phase.ERROR) {
                // Keep ERROR: the service's own stop after a crash must not hide the failure.
                setPhase(Phase.INSTALLED)
                finishStopRequest(requestId, null)
            } else {
                finishStopRequest(requestId, null)
            }
            return true
        }
        setPhase(Phase.STOPPING)
        p.stop()
        worker.execute { finishStopRequest(requestId, awaitStopResult(p)) }
        return true
    }

    private fun awaitStopResult(process: DshProcess): Throwable? = try {
        if (process.awaitExit()) null else IllegalStateException("runtime stop or root cleanup is unconfirmed")
    } catch (error: Throwable) {
        error
    }

    /** The default network changed: point the guest's resolv.conf at its DNS. */
    internal fun refreshDns(watch: NetworkAccess.Watch, properties: LinkProperties) {
        val gen = runGen
        val servers = GuestSetup.pickDns(properties.dnsServers)
        worker.execute {
            if ((phase == Phase.RUNNING || phase == Phase.STARTING) && installer.isCurrent()) {
                // Check ownership where this queued write executes, not only in the callback.
                // If a stop/start follows this check, its startup DNS write is later on this worker.
                if (gen != runGen || stopGen >= gen || !networkAccess.isCurrent(watch)) return@execute
                noteDns(GuestSetup.writeResolvConf(installer.rootfs, servers), DnsConfigSource.NETWORK)
            }
        }
    }

    private fun noteDns(report: DnsConfigReport, source: DnsConfigSource) {
        dnsLog.line(report, source, System.currentTimeMillis(), SystemClock.elapsedRealtime())?.let { log(it, "system") }
    }

    /** The system blocked or restored this app's network (DshService's default network callback). */
    internal fun onNetworkBlocked(watch: NetworkAccess.Watch, blocked: Boolean, inForeground: Boolean) {
        val line = networkAccess.onBlocked(watch, blocked, inForeground) ?: return
        log(line, "system")
        emitStatus()
    }

    internal fun onNetworkWatchStarted() = networkAccess.startWatch()

    /** Retire only this registration; a late old service must not reset its replacement. */
    internal fun onNetworkWatchStopped(watch: NetworkAccess.Watch) = networkAccess.reset(watch)

    /** A line of the app's own into the runtime log. */
    internal fun note(line: String) = log(line, "system")

    /**
     * Stops DSH and waits for it to exit, then deletes the runtime with everything in /root except
     * [keep], which the next install puts back.
     */
    @Synchronized fun reset(keep: Set<ResetKeep> = emptySet(), onDone: (Throwable?) -> Unit) {
        stopGen = runGen
        rootStartupGate.invalidate()
        resetting = true
        cancelPendingInstall()
        Thread({ PhoneControl.get(app).background.cancelUnwantedPending() }, "dsh-root-cancel").start()
        setPhase(Phase.STOPPING)
        worker.execute {
            try {
                check(retryPendingRootCleanup()) { "previous root cleanup is unconfirmed; retry reset after cleanup" }
                process?.let { p ->
                    p.stop()
                    check(p.awaitExit(15_000)) { "runtime stop or root cleanup is unconfirmed; retry reset after cleanup" }
                }
                check(retryPendingRootCleanup()) { "previous root cleanup is unconfirmed; retry reset after cleanup" }
                // A finished waiter may have reported incomplete cleanup. Captured identities
                // outlive its launcher; do not retire their rootfs while any still remain.
                if (!BuildConfig.ROOT_RUNTIME) {
                    val cleanup = DshProcess(app, manifest, installer.rootfs, this).reapStrays()
                    check(cleanup.complete) { "runtime stop incomplete; retry reset after cleanup" }
                }
                lastLaunch = null
                try {
                    check(runtimeLogs.pauseDisk()) { "log reset pause timed out" }
                    installer.reset(keep.flatMap { it.paths })
                } finally {
                    runtimeLogs.resumeDisk()
                }
                if (keep.isNotEmpty()) log("[dsh-app] reset keeps ${keep.joinToString { it.wire }}", "system")
                // The runtime is gone. Its safe-mode and failure record goes with it, in
                // memory first: the edit below drops the same keys from the preference
                // cache even when its commit, or the rollback's, fails.
                synchronized(this) {
                    // A fresh start: even kept plugins get a clean slate (auto safe mode still guards it).
                    strikes = 0
                    failure = null
                    mainFailure = null
                    safeState = SafeState.OFF
                    safeReason = null
                }
                workspaceRecovery.set(false) { next ->
                    val edit = prefs.edit().remove(K_SAFE_STATE).remove(K_SAFE_REASON).remove(K_STRIKES).remove(K_FAILURE)
                        .remove(K_MAIN_FAILURE).remove(K_MOVED)
                    if (next) edit.putBoolean(K_WORKSPACE_RECOVERY, true) else edit.remove(K_WORKSPACE_RECOVERY)
                    edit.commit()
                }
                synchronized(this) {
                    baseUrl = null
                    token = null
                    error = null
                    resetting = false
                    setPhase(Phase.NOT_INSTALLED)
                }
                main.post { onDone(null) }
            } catch (e: Throwable) {
                log("[dsh-app] reset failed: ${e.message}", "system")
                // Still stopping while the old process hasn't exited (its exit report moves the
                // phase on); otherwise the runtime is either still whole or gone.
                val installed = runCatching { installer.isCurrent() }.getOrDefault(false)
                synchronized(this) {
                    resetting = false
                    setPhase(when {
                        process != null -> Phase.STOPPING
                        installed -> Phase.INSTALLED
                        else -> Phase.NOT_INSTALLED
                    })
                }
                main.post { onDone(e) }
            }
        }
    }

    fun recentLogs(lines: Int): List<String> = runtimeLogs.recent(lines)
    fun logSnapshot(lines: Int): Map<String, Any?> = runtimeLogs.snapshot(lines).toMap()

    // ── Safe mode / last known good (called by RuntimePlugin) ───────────────────────

    fun autoSafe(): Boolean = prefs.getBoolean(K_AUTO_SAFE, true)

    fun setAutoSafe(enabled: Boolean) {
        prefs.edit().putBoolean(K_AUTO_SAFE, enabled).apply()
        emitStatus()
    }

    /** Hides the failure card; the strike count stays. */
    fun clearFailure() {
        failure = null
        prefs.edit().remove(K_FAILURE).apply()
        emitStatus()
    }

    /** Output around the failure on the card, else around the main profile's last failure (tokens masked). */
    fun failureLog(): List<String> {
        val name = when {
            failure != null -> LATEST_FAILURE_LOG
            mainFailure != null -> MAIN_FAILURE_LOG
            else -> return emptyList()
        }
        return try {
            File(logDir, name).readLines()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** What [restoreLastKnownGood] would change: `{available, at, disable, enable, changed, patchChanged, empty}`. */
    fun lastKnownGoodStatus(onDone: (Map<String, Any?>) -> Unit) {
        worker.execute {
            val result: Map<String, Any?> = try {
                val lkg = LastKnownGood.load(lkgFile)
                val current = lkgDisk()
                val failure = (current as? LkgDisk.Corrupt)?.cause ?: (current as? LkgDisk.Unreadable)?.cause
                if (lkg == null || current !is LkgDisk.Ok) {
                    mapOf("available" to false, "at" to lkg?.at) + if (failure != null) mapOf("error" to failure) else emptyMap()
                } else {
                    LkgPlan.of(lkg.snapshot, current.snapshot).toMap() + mapOf("available" to true, "at" to lkg.at)
                }
            } catch (e: Exception) {
                mapOf("available" to false, "error" to e.message)
            }
            main.post { onDone(result) }
        }
    }

    /** Puts the main profile's bundle list and settings layer back to the last good run's. */
    fun restoreLastKnownGood(onDone: (Map<String, Any?>?, Throwable?) -> Unit) {
        worker.execute {
            try {
                checkRecoveryWrite(mainProfileOnly = true)
                val lkg = LastKnownGood.load(lkgFile) ?: throw IllegalStateException(AppStrings.get(app, R.string.safe_no_good_config))
                val plan = restoreLkg(lkg.snapshot, SafeMode.stamp(),
                    AppStrings.get(app, R.string.safe_config_missing), AppStrings.get(app, R.string.safe_config_busy))
                log(
                    "[dsh-app] restored the last good plugin set from ${SafeMode.formatTime(lkg.at)}: " +
                        "disabled ${plan.disable}, enabled ${plan.enable}, settings layer ${if (plan.patchChanged) "restored" else "unchanged"}",
                    "system",
                )
                main.post { onDone(plan.toMap(), null) }
            } catch (e: Throwable) {
                log("[dsh-app] restoring the last good plugin set failed: ${e.message}", "system")
                main.post { onDone(null, e) }
            }
        }
    }

    /** Reads the main profile's settings layer for root builds, on the runtime worker. */
    fun readMainProfilePatch(onDone: (String?, Throwable?) -> Unit) {
        worker.execute {
            val outcome = runCatching {
                if (!BuildConfig.ROOT_RUNTIME) throw IllegalStateException("not a root runtime")
                RootProfileIo.readMainProfilePatch(app, installer.rootfs)
            }
            main.post { onDone(outcome.getOrNull(), outcome.exceptionOrNull()) }
        }
    }

    /** Atomically replaces the main profile's settings layer for root builds, on the runtime worker. */
    fun writeMainProfilePatch(content: String, onDone: (Boolean, Throwable?) -> Unit) {
        worker.execute {
            val outcome = runCatching {
                if (!BuildConfig.ROOT_RUNTIME) throw IllegalStateException("not a root runtime")
                openRootFileAccess(app).use { RootProfileIo.write(it, installer.rootfs, content) }
            }
            main.post { onDone(outcome.getOrDefault(false), outcome.exceptionOrNull()) }
        }
    }

    /** Main-profile file access: the root shell under Root600, plain files under proot. */
    private fun <T> withProfileFiles(block: (RuntimeFileAccess) -> T): T =
        if (BuildConfig.ROOT_RUNTIME) openRootFileAccess(app).use(block)
        else PlainRuntimeFileAccess.use(block)

    /** One main-profile read through [withProfileFiles], with the failure kept explicit. */
    private fun lkgDisk(): LkgDisk = withProfileFiles { files ->
        LastKnownGood.readDetailed(files, installer.rootfs)
    }

    /** The main profile as it is now; null when missing, corrupt or unreadable — the boot-time shape. */
    private fun readLkgSnapshot(): ProfileSnapshot? = try {
        (lkgDisk() as? LkgDisk.Ok)?.snapshot
    } catch (_: Exception) {
        null
    }

    /** Applies the last good snapshot under the shared writer lock, through the same seam. */
    private fun restoreLkg(snapshot: ProfileSnapshot, stamp: String, missingConfig: String, busyConfig: String): LkgPlan =
        withProfileFiles { files ->
            LastKnownGood.restore(files, installer.rootfs, snapshot, stamp, missingConfig, busyConfig)
        }

    /** Home-level files that would also break the safe profile. */
    fun safePreflight(onDone: (List<Map<String, Any?>>) -> Unit) {
        worker.execute {
            val hazards = try {
                SafeMode.preflight(installer.rootfs, AppStrings.get(app, R.string.safe_patch_hazard)) { names ->
                    AppStrings.get(app, R.string.safe_env_hazard, names.joinToString(if (AppStrings.isEnglish(app)) ", " else "、"))
                }.map { it.toMap() }
            } catch (e: Exception) {
                log("[dsh-app] safe-mode preflight could not inspect the guest: ${e.message}", "system")
                emptyList()
            }
            main.post { onDone(hazards) }
        }
    }

    /** Renames the given preflight files to `.off-<time>`; the briefing tells the agent about it. */
    fun moveAside(paths: List<String>, onDone: (List<Map<String, Any?>>?, Throwable?) -> Unit) {
        worker.execute {
            try {
                checkRecoveryWrite(mainProfileOnly = false)
                val outcome = SafeMode.moveAside(installer.rootfs, paths, SafeMode.stamp())
                val moved = outcome.moved
                if (moved.isNotEmpty()) {
                    val record = JSONArray(movedAside() + moved.map { (from, to) -> "$from → $to" })
                    prefs.edit().putString(K_MOVED, record.toString()).apply()
                    moved.forEach { (from, to) -> log("[dsh-app] moved $from aside to $to", "system") }
                }
                outcome.failure?.let { throw it }
                main.post { onDone(moved.map { (from, to) -> mapOf("from" to from, "to" to to) }, null) }
            } catch (e: Throwable) {
                main.post { onDone(null, e) }
            }
        }
    }

    /** Worker-only: another physical launch or disk action cannot interleave with the write. */
    private fun checkRecoveryWrite(mainProfileOnly: Boolean) {
        val state = synchronized(this) {
            RuntimeRecoveryGate.State(phase, process != null, runSafe, resetting)
        }
        RuntimeRecoveryGate.check(state, mainProfileOnly) {
            // onExit can follow an incomplete sweep; inspect the shared captured identities again.
            DshProcess(app, manifest, installer.rootfs, this).killStrays()
        }
    }

    private fun movedAside(): List<String> = try {
        val array = JSONArray(prefs.getString(K_MOVED, null) ?: "[]")
        List(array.length()) { array.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun briefing(): SafeMode.Briefing {
        val lkg = LastKnownGood.load(lkgFile)
        val log = try {
            File(logDir, MAIN_FAILURE_LOG).readLines()
        } catch (_: Exception) {
            emptyList()
        }
        val mainFailure = mainFailure
        return SafeMode.Briefing(
            failure = mainFailure,
            reason = safeReason,
            moved = movedAside(),
            lkgAt = lkg?.at,
            lkgBundles = lkg?.snapshot?.bundles ?: emptyList(),
            lkgVersions = lkg?.snapshot?.versions ?: emptyMap(),
            failureLog = if (mainFailure == null) emptyList() else log,
        )
    }

    private fun setSafeState(next: SafeState, reason: String?) {
        safeState = next
        safeReason = if (next == SafeState.OFF) null else reason
        val edit = prefs.edit().putString(K_SAFE_STATE, next.wire).putString(K_SAFE_REASON, safeReason)
        if (next == SafeState.OFF) edit.remove(K_MOVED)
        edit.apply()
    }

    /** Called two minutes after the login line if the same run is still up. */
    private fun onHealthy() {
        healthyAt = null
        if (runSafe) return
        val recovered = safeState == SafeState.PROBATION
        strikes = 0
        failure = null
        mainFailure = null
        prefs.edit().putInt(K_STRIKES, 0).remove(K_FAILURE).remove(K_MAIN_FAILURE).apply()
        if (safeState != SafeState.OFF) setSafeState(SafeState.OFF, null)
        val booted = bootProfile
        worker.execute {
            try {
                if (LastKnownGood.pinBooted(booted) { LastKnownGood.save(lkgFile, it, System.currentTimeMillis()) }) {
                    log("[dsh-app] DSH has been up for 2 minutes; recorded its plugin set as the last good one", "system")
                }
            } catch (e: Exception) {
                log("[dsh-app] could not record the last good plugin set: ${e.message}", "system")
            }
        }
        if (recovered) notice(AppStrings.get(app, R.string.safe_normal_recovered))
        emitStatus()
    }

    /** Bundles enabled or upgraded since the last good run: the first suspects of a new failure. */
    private fun recentChanges(): List<String> = try {
        val lkg = LastKnownGood.load(lkgFile)
        val current = bootProfile ?: readLkgSnapshot()
        if (lkg == null || current == null) {
            emptyList()
        } else {
            val plan = LkgPlan.of(lkg.snapshot, current)
            (plan.disable + plan.changed).distinct()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun recordFailure(f: RunFailure, tail: List<String>) {
        failure = f
        val json = JSONObject(f.toMap()).toString()
        val edit = prefs.edit().putString(K_FAILURE, json)
        // A later kill from outside says nothing about the profile: keep the plugin failure it followed.
        val main = !f.safeMode && (f.kind != FailureKind.KILLED || mainFailure == null)
        if (main) {
            mainFailure = f
            edit.putString(K_MAIN_FAILURE, json)
        }
        edit.apply()
        try {
            logDir.mkdirs()
            val text = tail.joinToString("\n", postfix = "\n") { CrashClassifier.redact(it) }
            File(logDir, LATEST_FAILURE_LOG).writeText(text)
            if (main) File(logDir, MAIN_FAILURE_LOG).writeText(text)
        } catch (_: Exception) {
        }
    }

    private fun readFailure(key: String): RunFailure? = try {
        prefs.getString(key, null)?.let { text ->
            val json = JSONObject(text)
            val kind = FailureKind.parse(json.optString("kind")) ?: return@let null
            fun list(name: String): List<String> {
                val array = json.optJSONArray(name) ?: return emptyList()
                return List(array.length()) { array.optString(it) }
            }
            RunFailure(
                kind = kind,
                exitCode = json.optInt("exitCode"),
                at = json.optLong("at"),
                safeMode = json.optBoolean("safeMode"),
                summary = if (json.isNull("summary")) null else json.optString("summary"),
                requiredRows = list("requiredRows"),
                suspects = list("suspects"),
                recent = list("recent"),
                diagnostics = if (json.isNull("diagnostics")) null else json.optString("diagnostics"),
                upSeconds = if (json.isNull("upSeconds")) null else json.optLong("upSeconds"),
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun notice(text: String) = emit(mapOf("type" to "notice", "text" to text))

    // DshProcess.Listener

    override fun onLog(line: String, stream: String) {
        log(line, stream)
    }

    override fun onTidying(active: Boolean) {
        if (tidyingFiles == active) return
        tidyingFiles = active
        emitStatus()
    }

    override fun onStartupFailure(source: DshProcess) {
        // failStart holds the process monitor; stopProcess takes controller -> process.
        // Never take the controller monitor synchronously on that callback thread.
        main.post {
            synchronized(this) {
                if (source !== process) return@synchronized
                source.launchDiagnostics.failed("startFailed")
                publishStartupFailure(source.startupError, ownerReleased = false)
            }
        }
    }

    /** Local startup errors supersede only the latest card, not the main-profile history or strikes. */
    private fun publishStartupFailure(message: String?, ownerReleased: Boolean): Boolean {
        val next = StartupFailureState.of(message, ownerReleased, resetting) ?: return false
        error = next.error
        if (failure != null) {
            failure = null
            prefs.edit().remove(K_FAILURE).apply()
        }
        setPhase(next.phase)
        return true
    }

    @Synchronized override fun onReady(source: DshProcess, baseUrl: String, token: String) {
        // A late report from a stopped or replaced process.
        if (!acceptsRunReady(source === process, phase, runGen, stopGen, resetting) || source.startupError != null) return
        DshService.cancelStoppedAlert(app)
        this.baseUrl = baseUrl
        this.token = token
        if ((phase == Phase.STARTING || phase == Phase.RUNNING) &&
            stopGen < runGen && !resetting) {
            managedSkillGate.ready(runGen, token)
        }
        readyAt = SystemClock.elapsedRealtime()
        if (failure != null) {
            // This run got going, so the previous failure card is stale; strikes reset only when healthy.
            failure = null
            prefs.edit().remove(K_FAILURE).apply()
        }
        if (!runSafe) {
            // First boot creates the profile after startProcess reads it. Capture it before
            // RUNNING lets the app change it; a later disk read would not describe this boot.
            bootProfile = LastKnownGood.bootedAtReady(bootProfile) { readLkgSnapshot() }
        }
        if (!runSafe) healthyAt = System.currentTimeMillis() + SafePolicy.HEALTHY_MS
        log("[dsh-app] DSH is listening at $baseUrl${if (runSafe) " (safe mode)" else ""}", "system")
        timeline.ready()?.let { log(it, "system") }
        setPhase(Phase.RUNNING)
        PhoneControl.get(app).onEngineReady()
        val gen = runGen
        main.postDelayed({ if (gen == runGen && phase == Phase.RUNNING && process != null) onHealthy() }, SafePolicy.HEALTHY_MS)
    }

    @Synchronized override fun onExit(source: DshProcess, code: Int, expected: Boolean) {
        if (source !== process) return
        if (BuildConfig.ROOT_RUNTIME) {
            if (source.rootCleanupConfirmed) {
                clearPendingRootCleanup(source)
            } else {
                retainPendingRootCleanup(source)
            }
        }
        try {
            if (source.startupError != null) source.launchDiagnostics.failed("startFailed")
            else if (!expected && stopGen < runGen) source.launchDiagnostics.failed("exit:$code")
            process = null
            PhoneControl.get(app).onEngineStopped()
            baseUrl = null
            token = null
            healthyAt = null
            log("[dsh-app] DSH exited with code $code", "system")
            val tail = synchronized(runTail) { runTail.toList() }
            var f = CrashClassifier.classify(code, readyAt, SystemClock.elapsedRealtime(), System.currentTimeMillis(), tail, runSafe, source.direct)
            val startupError = source.startupError
            // A token deadline must not hide crash evidence already in the tail (#75); a silent
            // loader or a STOP that followed still ends as a plain startup failure.
            val classifyStartup = startupError != null && !expected && stopGen < runGen &&
                CrashClassifier.classifyAfterTokenTimeout(source.startupTokenTimedOut, f)
            if (!classifyStartup && publishStartupFailure(startupError, ownerReleased = true)) {
                // An accepted failure survives a later STOP. Reset still owns its final state.
                // Neither a deadline nor a local launch error is evidence against a plugin.
                if (!resetting) DshService.postStoppedAlert(app)
                DshService.stopSelfIfRunning(app, runGen)
                return
            }
            if (expected || stopGen >= runGen) {
                if (!resetting && phase == Phase.STOPPING) setPhase(Phase.INSTALLED)
                DshService.stopSelfIfRunning(app, runGen)
                return
            }
            if (SafePolicy.directLaunchFailed(f, source.direct)) {
                // Direct can't launch on this device: PRoot from now on with this runtime, no failure card,
                // no strike. A new runtime, or the user turning Direct on again, gives it another try.
                prefs.edit().putString(K_DIRECT_FAILED, manifest.runtimeVersion).apply()
                log("[dsh-app] direct launch failed (${f.summary}); starting again with compatible launch", "system")
                restartNow("with compatible launch")
                return
            }
            if (!runSafe) f = f.copy(recent = recentChanges())
            val outcome = SafePolicy.onFailure(safeState, strikes, f, autoSafe(),
                AppStrings.get(app, R.string.safe_probation_failed), AppStrings.get(app, R.string.safe_auto_started))
            val now = SystemClock.elapsedRealtime()
            val delay = SafePolicy.autoRestartDelay(f.kind, code, readyAt?.let { now - it }, autoRestarts.toList(), now)
            if (!outcome.restartSafe && delay != null && DshService.isRunning) {
                // Killed from outside after a good run (memory pressure, a phantom-process sweep): try again.
                autoRestarts.removeAll { now - it >= 600_000L }
                autoRestarts.addLast(now)
                val gen = runGen
                setPhase(Phase.STARTING)
                log("[dsh-app] killed from outside; restarting in ${delay / 1000}s", "system")
                main.postDelayed({
                    if (gen == runGen && stopGen < gen && phase == Phase.STARTING && process == null && DshService.isRunning) {
                        startProcess()
                    }
                }, delay)
                return
            }
            recordFailure(f, tail)
            strikes = outcome.strikes
            prefs.edit().putInt(K_STRIKES, strikes).apply()
            if (outcome.state != safeState) setSafeState(outcome.state, outcome.reason ?: safeReason)
            log("[dsh-app] classified as ${f.kind.wire}${if (f.safeMode) " (safe mode)" else ""}; consecutive failures: $strikes", "system")
            error = startupError?.let { "start failed: $it" } ?: "DSH exited unexpectedly (code $code)"
            if (outcome.restartSafe) {
                log("[dsh-app] restarting in safe mode", "system")
                outcome.notice?.let { notice(it) }
                restartNow("in safe mode")
            } else {
                setPhase(Phase.ERROR)
                DshService.postStoppedAlert(app)
                DshService.stopSelfIfRunning(app, runGen)
            }
        } finally {
            runtimeLogs.flush()
        }
    }

    @Synchronized private fun retainPendingRootCleanup(source: DshProcess) {
        if (BuildConfig.ROOT_RUNTIME && !source.rootCleanupConfirmed && pendingRootCleanup == null) {
            pendingRootCleanup = source
        }
    }

    @Synchronized private fun clearPendingRootCleanup(source: DshProcess) {
        pendingRootCleanup = clearPendingIfSameOwner(
            pendingRootCleanup,
            source,
            source.rootCleanupConfirmed,
        )
    }

    private fun retryPendingRootCleanup(): Boolean {
        val pending = synchronized(this) { pendingRootCleanup } ?: return true
        if (!pending.rootCleanupConfirmed) {
            pending.stop()
            if (!pending.awaitExit(15_000)) return false
        }
        if (!pending.rootCleanupConfirmed) return false
        clearPendingRootCleanup(pending)
        return true
    }

    /** Starts again right away; [startProcess] picks the mode. [how] names it if that fails. */
    private fun restartNow(how: String) {
        val gen = runGen
        main.post {
            if (gen != runGen || stopGen >= gen) return@post
            if (DshService.isRunning) {
                startProcess()
            } else {
                phase = Phase.INSTALLED
                try {
                    start(pendingEnv, pendingPhoneStorage)
                } catch (e: Exception) {
                    log("[dsh-app] could not restart $how: ${e.message}", "system")
                    setPhase(Phase.ERROR)
                }
            }
        }
    }

    private fun log(line: String, stream: String) = runtimeLogs.accept(line, stream)

    private fun setPhase(next: Phase) {
        if (next != Phase.RUNNING) managedSkillGate.retire()
        phase = next
        emitStatus()
        DshService.updateNotification(app)
    }

    private fun emitStatus() = synchronized(statusPublishLock) {
        emit(mapOf("type" to "status", "status" to status()))
    }

    private fun emit(event: Map<String, Any?>) {
        val targets = synchronized(sinks) { sinks.toList() }
        if (targets.isEmpty()) return
        main.post { targets.forEach { it.emit(event) } }
    }

    companion object {
        internal fun installAllowed(
            phase: Phase,
            resetting: Boolean,
            processActive: Boolean,
        ): Boolean =
            phase != Phase.INSTALLING &&
                phase !in setOf(Phase.RUNNING, Phase.STARTING, Phase.STOPPING) &&
                !resetting &&
                !(phase == Phase.ERROR && processActive)

        private const val PREFS = "dsh_runtime"
        private const val K_SAFE_STATE = "safeState"
        private const val K_SAFE_REASON = "safeReason"
        private const val K_STRIKES = "strikes"
        private const val K_FAILURE = "failure"
        private const val K_MAIN_FAILURE = "mainFailure"
        private const val K_MOVED = "movedAside"
        private const val K_AUTO_SAFE = "autoSafe"
        private const val K_WORKSPACE_RECOVERY = "workspaceRecovery"
        private const val K_DIRECT_ENGINE = "directEngine"
        /** Runtime version Direct failed to launch with (see [SafePolicy.directLaunchFailed]). */
        private const val K_DIRECT_FAILED = "directEngineFailedRuntime"
        private const val MAIN_FAILURE_LOG = "failure-normal.log"
        private const val LATEST_FAILURE_LOG = "failure-latest.log"

        @Volatile private var instance: RuntimeController? = null

        fun get(context: Context): RuntimeController =
            instance ?: synchronized(this) {
                instance ?: RuntimeController(context.applicationContext).also { instance = it }
            }
    }
}

internal fun <T : Any> clearPendingIfSameOwner(pending: T?, completed: T, confirmed: Boolean): T? =
    if (pending === completed && confirmed) null else pending

/** The same accepted local failure while cleaning, after exit, or while reset still owns the disk. */
internal data class StartupFailureState(val error: String, val phase: RuntimeController.Phase) {
    companion object {
        fun of(message: String?, ownerReleased: Boolean, resetting: Boolean): StartupFailureState? {
            if (message == null) return null
            val phase = if (ownerReleased && !resetting) RuntimeController.Phase.ERROR
                else RuntimeController.Phase.STOPPING
            return StartupFailureState("start failed: $message", phase)
        }
    }
}

internal class WorkspaceRecoverySaveException(cause: Throwable? = null) :
    IllegalStateException("could not save workspace recovery mode; retry before starting", cause)

/** Worker-owned changes; status and launch use only the last successfully committed value. */
internal class WorkspaceRecoveryMode(initial: Boolean) {
    @Volatile var enabled: Boolean = initial
        private set

    fun set(next: Boolean, persist: (Boolean) -> Boolean) {
        val previous = enabled
        try {
            if (!persist(next)) throw WorkspaceRecoverySaveException()
        } catch (error: Exception) {
            // SharedPreferences updates its memory cache even when commit returns false.
            // Best-effort rollback avoids a later unrelated edit persisting that failed change.
            // A failed rollback still does not change this runtime owner's confirmed value.
            runCatching { persist(previous) }
            throw if (error is WorkspaceRecoverySaveException) error else WorkspaceRecoverySaveException(error)
        }
        enabled = next
    }

    fun applyEnvironment(environment: MutableMap<String, String>) {
        environment["DSH_APP_WORKSPACE_RECOVERY"] = if (enabled) "1" else "0"
    }
}
