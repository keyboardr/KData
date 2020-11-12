package com.keyboardr.kdata

import androidx.annotation.MainThread
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.experimental.ExperimentalTypeInference
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@OptIn(ExperimentalTime::class)
internal val DEFAULT_TIMEOUT = 5.seconds

public interface KDataScope<T> {
  public suspend fun emit(value: T)

  public suspend fun emitSource(source: KData<T>): DisposableHandle

  public val latestValue: T?
}

internal class KDataScopeImpl<T>(
  private val target: CoroutineKLiveData<T>,
  context: CoroutineContext
) : KDataScope<T> {
  override val latestValue: T?
    get() = target.value

  // use provided context + main dispatcher to communicate with the target
  // KData. This gives us main thread safety as well as cancellation cooperation
  private val coroutineContext = context + Dispatchers.Main.immediate

  override suspend fun emitSource(source: KData<T>): DisposableHandle = withContext(coroutineContext) {
    return@withContext target.emitSource(source)
  }

  override suspend fun emit(value: T) = withContext(coroutineContext) {
    target.clearSource()
    target.setValue(value)
  }
}

internal suspend fun <T> MediatorKLiveData<T>.addDisposableSource(
    source: KData<T>
): EmittedSource = withContext(Dispatchers.Main.immediate) {
  addSource(source) {
    setValue(it)
  }
  EmittedSource(
      source = source,
      mediator = this@addDisposableSource
  )
}

/**
 * Holder class that keeps track of the previously dispatched [KData].
 * It implements [DisposableHandle] interface while also providing a suspend clear function
 * that we can use internally.
 */
internal class EmittedSource(
  private val source: KData<*>,
  private val mediator: MediatorKLiveData<*>
) : DisposableHandle {
  // @MainThread
  private var disposed = false

  /**
   * Unlike [dispose] which cannot be sync because it not a coroutine (and we do not want to
   * lock), this version is a suspend function and does not return until source is removed.
   */
  suspend fun disposeNow() = withContext(Dispatchers.Main.immediate) {
    removeSource()
  }

  override fun dispose() {
    CoroutineScope(Dispatchers.Main.immediate).launch {
      removeSource()
    }
  }

  @MainThread
  private fun removeSource() {
    if (!disposed) {
      mediator.removeSource(source)
      disposed = true
    }
  }
}

internal typealias Block<T> = suspend KDataScope<T>.() -> Unit

@OptIn(ExperimentalTime::class)
internal class BlockRunner<T>(
  private val kData: CoroutineKLiveData<T>,
  private val block: Block<T>,
  private val timeout: Duration,
  private val scope: CoroutineScope,
  private val onDone: () -> Unit
) {
  // currently running job.
  private var runningJob: Job? = null

  // cancellation job created in cancel.
  private var cancellationJob: Job? = null

  @MainThread
  fun maybeRun() {
    cancellationJob?.cancel()
    cancellationJob = null
    if (runningJob != null) {
      return
    }
    runningJob = scope.launch {
      val kDataScope = KDataScopeImpl(kData, coroutineContext)
      block(kDataScope)
      onDone()
    }
  }

  @MainThread
  fun cancel() {
    if (cancellationJob != null) {
      error("Cancel call cannot happen without a maybeRun")
    }
    cancellationJob = scope.launch(Dispatchers.Main.immediate) {
      delay(timeout)
      if (!kData.hasActiveObservers) {
        // one last check on active observers to avoid any race condition between starting
        // a running coroutine and cancelation
        runningJob?.cancel()
        runningJob = null
      }
    }
  }
}

@OptIn(ExperimentalTime::class)
internal class CoroutineKLiveData<T>(
  context: CoroutineContext = EmptyCoroutineContext,
  timeout: Duration = DEFAULT_TIMEOUT,
  block: Block<T>
) : MediatorKLiveData<T>() {
  private var blockRunner: BlockRunner<T>?
  private var emittedSource: EmittedSource? = null

  init {
    // use an intermediate supervisor job so that if we cancel individual block runs due to losing
    // observers, it won't cancel the given context as we only cancel w/ the intention of possibly
    // relaunching using the same parent context.
    val supervisorJob = SupervisorJob(context[Job])
    // The scope for this KData where we launch every block Job.
    // We default to Main dispatcher but developer can override it.
    // The supervisor job is added last to isolate block runs.
    val scope = CoroutineScope(Dispatchers.Main.immediate + context + supervisorJob)
    blockRunner = BlockRunner(
        kData = this,
        block = block,
        timeout = timeout,
        scope = scope
    ) {
      blockRunner = null
    }
  }

  internal suspend fun emitSource(source: KData<T>): DisposableHandle {
    clearSource()
    return addDisposableSource(source).also { emittedSource = it }
  }

  internal suspend fun clearSource() {
    emittedSource?.disposeNow()
    emittedSource = null
  }

  override fun onActive() {
    super.onActive()
    blockRunner?.maybeRun()
  }

  override fun onInactive() {
    super.onInactive()
    blockRunner?.cancel()
  }
}

@OptIn(ExperimentalTypeInference::class, ExperimentalTime::class)
public fun <T> kData(
  context: CoroutineContext = EmptyCoroutineContext,
  timeout: Duration = DEFAULT_TIMEOUT,
  @BuilderInference block: suspend KDataScope<T>.() -> Unit
): KData<T> = CoroutineKLiveData(context, timeout, block)
