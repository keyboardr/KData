package com.keyboardr.kdata

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * Creates a KData that has values collected from the origin [Flow].
 *
 * The upstream flow collection starts when the returned [KData] becomes active
 * ([KLiveData.onActive]).
 * If the [KData] becomes inactive ([KLiveData.onInactive]) while the flow has not completed,
 * the flow collection will be cancelled after [timeout] milliseconds unless the [KData]
 * becomes active again before that timeout (to gracefully handle cases like Activity rotation).
 *
 * After a cancellation, if the [KData] becomes active again, the upstream flow collection will
 * be re-executed.
 *
 * If the upstream flow completes successfully *or* is cancelled due to reasons other than
 * [KData] becoming inactive, it *will not* be re-collected even after [KData] goes through
 * active inactive cycle.
 *
 * If flow completes with an exception, then exception will be delivered to the
 * [CoroutineExceptionHandler][kotlinx.coroutines.CoroutineExceptionHandler] of provided [context].
 * By default [EmptyCoroutineContext] is used to so an exception will be delivered to main's
 * thread [UncaughtExceptionHandler][Thread.UncaughtExceptionHandler]. If your flow upstream is
 * expected to throw, you can use [catch operator][kotlinx.coroutines.flow.catch] on upstream flow
 * to emit a helpful error object.
 *
 * The [timeout] can be changed to fit different use cases better, for example increasing it
 * will give more time to flow to complete before being canceled and is good for finite flows
 * that are costly to restart. Otherwise if a flow is cheap to restart decreasing the [timeout]
 * value will allow to produce less values that aren't consumed by anything.
 *
 * @param context The CoroutineContext to collect the upstream flow in. Defaults to
 * [EmptyCoroutineContext] combined with
 * [Dispatchers.Main.immediate][kotlinx.coroutines.MainCoroutineDispatcher.immediate]
 * @param timeout The timeout in ms before cancelling the block if there are no active observers
 * ([KData.hasActiveObservers]. Defaults to [DEFAULT_TIMEOUT].
 */
@InternalCoroutinesApi
@OptIn(ExperimentalTime::class)
@JvmOverloads
public fun <T> Flow<T>.asKData(
    context: CoroutineContext = EmptyCoroutineContext,
    timeout: Duration = DEFAULT_TIMEOUT
): KData<T> = kData(context, timeout) {
  collect {
    emit(it)
  }
}

/**
 * Creates a [Flow] containing values dispatched by originating [KData]: at the start
 * a flow collector receives the latest value held by LiveData and then observes LiveData updates.
 *
 * When a collection of the returned flow starts the originating [KData] becomes
 * [active][KLiveData.onActive]. Similarly, when a collection completes [KData] becomes
 * [inactive][KLiveData.onInactive].
 *
 * BackPressure: the returned flow is conflated. There is no mechanism to suspend an emission by
 * LiveData due to a slow collector, so collector always gets the most recent value emitted.
 */
public fun <T> KData<T>.asFlow(): Flow<T> = flow {
  val channel = Channel<T>(Channel.CONFLATED)
  val observer = Observer<T> {
    channel.offer(it)
  }
  withContext(Dispatchers.Main.immediate) {
    observeForever(observer)
  }
  try {
    for (value in channel) {
      emit(value)
    }
  } finally {
    GlobalScope.launch(Dispatchers.Main.immediate) {
      removeObserver(observer)
    }
  }
}