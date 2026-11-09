import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../runtime/runtime_bridge.dart';
import '../runtime/runtime_state.dart';

/// Multi-step engine actions built on [localRuntimeProvider].
///
/// They take the [ProviderContainer] (not a WidgetRef) so they keep working after
/// the screen that started them is popped.
abstract final class EngineControl {
  /// Stops the local DSH, waits until the process has actually exited, then starts it again.
  /// [mode] null keeps the current mode (a restart in safe mode stays in safe mode).
  static Future<void> restart(
    ProviderContainer container, {
    EngineMode? mode,
    bool Function()? isCurrent,
    Future<void>? cancelRequested,
    Duration stopTimeout = const Duration(seconds: 20),
  }) async {
    final runtime = container.read(localRuntimeProvider.notifier);
    final phase = container.read(localRuntimeProvider).status.phase;
    if (phase == RuntimePhase.running || phase == RuntimePhase.starting || phase == RuntimePhase.stopping) {
      await _stopAndWait(container, timeout: stopTimeout);
    }
    if (isCurrent != null && !isCurrent()) throw StateError('restart cancelled');
    await _startAndWait(
      container,
      () => mode == null ? runtime.startOwned() : runtime.startInOwned(mode),
      isCurrent: isCurrent,
      cancelRequested: cancelRequested,
      stopOwnedStart: runtime.stopOwnedStart,
    );
  }

  /// Changes the local index mode only after stopping, then starts the same profile.
  /// A rejected stop or write cannot start a new run.
  static Future<void> setWorkspaceRecovery(ProviderContainer container, {required bool enabled}) async {
    await _stopForRecovery(container);
    await container.read(runtimeBridgeProvider).setWorkspaceRecovery(enabled);
    await _restartForRecovery(container, afterRecovery: true);
  }

  /// Starts the engine unless it is already starting or running.
  static Future<void> ensureStarted(ProviderContainer container) async {
    final phase = container.read(localRuntimeProvider).status.phase;
    if (phase == RuntimePhase.installed || phase == RuntimePhase.error) {
      await container.read(localRuntimeProvider.notifier).start();
    }
  }

  /// Whether the launch-time auto start should run. After two failures in a row with
  /// automatic safe mode turned off, the failure card waits for the user instead.
  /// In every other case `start()` itself picks the right mode, so callers need
  /// nothing else.
  static bool shouldAutoStart(RuntimeStatus status) =>
      !(status.safeState == SafeModeState.off && !status.autoSafe && status.strikes >= 2);

  /// Stops before renaming home-level files, then starts the safe profile.
  static Future<void> enterSafeMode(ProviderContainer container, {List<String> moveAside = const []}) async {
    if (moveAside.isEmpty) {
      await _restartForRecovery(container, mode: EngineMode.safe);
      return;
    }
    await _stopForRecovery(container);
    await container.read(runtimeBridgeProvider).moveAside(moveAside);
    await _restartForRecovery(container, mode: EngineMode.safe, afterRecovery: true);
  }

  /// Back to the user's own profile; failing again within two minutes returns to safe mode.
  static Future<void> leaveSafeMode(ProviderContainer container) => restart(container, mode: EngineMode.normal);

  /// Puts the main profile back to its last good plugin set, then starts in normal mode
  /// (from safe mode that is the two-minute trial). Needs no model, key or network.
  static Future<LastKnownGood> restoreLastKnownGood(ProviderContainer container) async {
    final status = container.read(localRuntimeProvider).status;
    final busy =
        status.phase == RuntimePhase.running ||
        status.phase == RuntimePhase.starting ||
        status.phase == RuntimePhase.stopping;
    // Only a currently running safe profile may keep running during this write.
    final safeRunning = status.inSafeMode && status.phase == RuntimePhase.running;
    if (busy && !safeRunning) await _stopForRecovery(container);
    final changed = await container.read(runtimeBridgeProvider).restoreLastKnownGood();
    await _restartForRecovery(container, mode: EngineMode.normal, afterRecovery: true);
    return changed;
  }

  static Future<void> _stopForRecovery(ProviderContainer container) async {
    try {
      await _stopAndWait(container);
    } on TimeoutException {
      throw PlatformException(code: 'runtime_not_stopped');
    }
  }

  static Future<void> _restartForRecovery(
    ProviderContainer container, {
    EngineMode? mode,
    bool afterRecovery = false,
  }) async {
    try {
      await restart(container, mode: mode);
    } on TimeoutException {
      throw PlatformException(code: afterRecovery ? 'runtime_not_stopped_after_recovery' : 'runtime_not_stopped');
    }
  }

  static Future<void> _stopAndWait(
    ProviderContainer container, {
    Duration timeout = const Duration(seconds: 20),
  }) async {
    bool stoppedNow() {
      final phase = container.read(localRuntimeProvider).status.phase;
      return !phase.isBusy && phase != RuntimePhase.running;
    }

    if (stoppedNow()) return;
    final stopped = Completer<void>();
    final sub = container.listen<RuntimePhase>(localRuntimeProvider.select((s) => s.status.phase), (_, next) {
      if (!next.isBusy && next != RuntimePhase.running && !stopped.isCompleted) {
        stopped.complete();
      }
    });
    try {
      final stop = container.read(localRuntimeProvider).status.phase == RuntimePhase.stopping
          ? Future<void>.value()
          : container.read(localRuntimeProvider.notifier).stop();
      if (stoppedNow() && !stopped.isCompleted) stopped.complete();
      // A stopped status alone is insufficient: Root cleanup must acknowledge too.
      await Future.wait<void>([stop, stopped.future], eagerError: true).timeout(timeout);
    } finally {
      sub.close();
    }
  }

  static Future<void> _startAndWait(
    ProviderContainer container,
    Future<int> Function() start, {
    bool Function()? isCurrent,
    Future<void>? cancelRequested,
    required Future<void> Function(int ticket) stopOwnedStart,
  }) async {
    final ready = Completer<void>();
    var starting = false;
    void observe(RuntimePhase phase) {
      if (phase == RuntimePhase.starting) starting = true;
      if (phase == RuntimePhase.running) {
        if (!ready.isCompleted) ready.complete();
      } else if (starting &&
          (phase == RuntimePhase.error || phase == RuntimePhase.installed || phase == RuntimePhase.notInstalled)) {
        if (!ready.isCompleted) {
          final error = container.read(localRuntimeProvider).status.error;
          ready.completeError(StateError(error?.isNotEmpty == true ? error! : 'runtime stopped before becoming ready'));
        }
      }
    }

    final sub = container.listen<RuntimePhase>(
      localRuntimeProvider.select((s) => s.status.phase),
      (_, next) => observe(next),
    );
    final readyResult = ready.future.then<(bool, Object?, StackTrace?)>(
      (_) => (false, null, null),
      onError: (Object error, StackTrace stack) => (false, error, stack),
    );
    final cancelResult = cancelRequested?.then<(bool, Object?, StackTrace?)>((_) => (true, null, null));
    final outcomeFuture = cancelResult == null ? readyResult : Future.any([readyResult, cancelResult]);
    try {
      if (isCurrent != null && !isCurrent()) throw StateError('restart cancelled');
      final startTicket = await start();
      observe(container.read(localRuntimeProvider).status.phase);
      if (startTicket <= 0) throw StateError('runtime start returned no owner ticket');
      final (wasCancelled, error, stack) = await outcomeFuture;
      if (wasCancelled || (isCurrent != null && !isCurrent())) {
        await stopOwnedStart(startTicket);
        throw StateError('restart cancelled');
      }
      if (error != null) Error.throwWithStackTrace(error, stack!);
    } finally {
      sub.close();
    }
  }
}
