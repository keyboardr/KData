package com.keyboardr.kdata

import android.os.Looper
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.State.DESTROYED
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.keyboardr.kdata.internal.Actual
import com.keyboardr.kdata.internal.Maybe
import com.keyboardr.kdata.internal.NotSet
import com.keyboardr.kdata.internal.SafeIterableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

public fun interface Observer<in T> {
  public fun onChanged(value: T)
}

public interface KData<T> {
  public fun observe(owner: LifecycleOwner, observer: Observer<T>)
  public fun observeForever(observer: Observer<T>)
  public fun removeObserver(observer: Observer<T>)
  public fun removeObservers(owner: LifecycleOwner)

  public val value: T?
  public val hasObservers: Boolean
  public val hasActiveObservers: Boolean
}

/**
 * KLiveData is a data holder class that can be observed within a given lifecycle.
 * This means that an [Observer] can be added in a pair with a [LifecycleOwner], and
 * this observer will be notified about modifications of the wrapped data only if the paired
 * LifecycleOwner is in active state. LifecycleOwner is considered as active, if its state is
 * [Lifecycle.State.STARTED] or [Lifecycle.State.RESUMED]. An observer added via
 * [observeForever] is considered as always active and thus will be always notified
 * about modifications. For those observers, you should manually call
 * [removeObserver].
 *
 * An observer added with a Lifecycle will be automatically removed if the corresponding
 * Lifecycle moves to [Lifecycle.State.DESTROYED] state. This is especially useful for
 * activities and fragments where they can safely observe KLiveData and not worry about leaks:
 * they will be instantly unsubscribed when they are destroyed.
 *
 * In addition, KLiveData has [KLiveData.onActive] and [KLiveData.onInactive] methods
 * to get notified when number of active [Observer]s change between 0 and 1.
 * This allows KLiveData to release any heavy resources when it does not have any Observers that
 * are actively observing.
 *
 * @param <T> The type of data held by this instance
 */
public abstract class KLiveData<T> : KData<T> {
  private val dataLock = Any()

  private val observers = SafeIterableMap<Observer<T>, ObserverWrapper>()

  private var activeCount = 0
  private var changingActiveState = false

  @Volatile
  private var mData: Maybe<T>

  @Volatile
  private var pendingData: Maybe<T> = NotSet
  internal var version: Int

  private var dispatchingValue = false
  private var dispatchInvalidated = false

  protected constructor() {
    mData = NotSet
    version = START_VERSION
  }

  protected constructor(value: T) {
    mData = Actual(value)
    version = START_VERSION + 1
  }

  private fun considerNotify(observer: ObserverWrapper) {
    if (!observer.active) {
      return
    }
    // Check latest state b4 dispatch. Maybe it changed state but we didn't get the event yet.
    //
    // we still first check observer.active to keep it as the entrance for events. So even if
    // the observer moved to an active state, if we've not received that event, we better not
    // notify for a more predictable notification order.
    if (!observer.shouldBeActive) {
      observer.activeStateChanged(false)
      return
    }
    if (observer.lastVersion >= version) {
      return
    }
    observer.lastVersion = version
    @Suppress("UNCHECKED_CAST")
    observer.observer.onChanged(value as T)
  }

  private fun dispatchingValue(initiator: ObserverWrapper? = null) {
    if (dispatchingValue) {
      dispatchInvalidated = true
      return
    }

    var localInitiator = initiator

    dispatchingValue = true
    do {
      dispatchInvalidated = false
      if (localInitiator != null) {
        considerNotify(localInitiator)
        localInitiator = null
      } else {
        val iterator = observers.iteratorWithAdditions()
        while (iterator.hasNext()) {
          considerNotify(iterator.next().value)
          if (dispatchInvalidated) {
            break
          }
        }
      }
    } while (dispatchInvalidated)
  }

  /**
   * Adds the given observer to the observers list within the lifespan of the given
   * owner. The events are dispatched on the main thread. If KLiveData already has data
   * set, it will be delivered to the observer.
   *
   * The observer will only receive events if the owner is in [Lifecycle.State.STARTED]
   * or [Lifecycle.State.RESUMED] state (active).
   *
   * If the owner moves to the [Lifecycle.State.DESTROYED] state, the observer will
   * automatically be removed.
   *
   * When data changes while the `owner` is not active, it will not receive any updates.
   * If it becomes active again, it will receive the last available data automatically.
   *
   * KLiveData keeps a strong reference to the observer and the owner as long as the
   * given LifecycleOwner is not destroyed. When it is destroyed, KLiveData removes references to
   * the observer &amp; the owner.
   *
   * If the given owner is already in [Lifecycle.State.DESTROYED] state, KLiveData
   * ignores the call.
   *
   * If the given owner, observer tuple is already in the list, the call is ignored.
   * If the observer is already in the list with another owner, KLiveData throws an
   * [IllegalArgumentException].
   *
   * @param owner    The LifecycleOwner which controls the observer
   * @param observer The observer that will receive the events
   */
  @MainThread
  override fun observe(owner: LifecycleOwner, observer: Observer<T>) {
    assertMainThread("observe")
    if (owner.lifecycle.currentState == DESTROYED) return
    val wrapper = LifecycleBoundObserver(owner, observer)
    val existing: ObserverWrapper? = observers.putIfAbsent(observer, wrapper)
    when {
      existing == null -> owner.lifecycle.addObserver(wrapper)
      !existing.isAttachedTo(owner) -> throw IllegalArgumentException(
          "Cannot add the same observer with different lifecycles")
    }
  }

  /**
   * Adds the given observer to the observers list. This call is similar to
   * [observe] with a LifecycleOwner, which
   * is always active. This means that the given observer will receive all events and will never
   * be automatically removed. You should manually call [.removeObserver] to stop
   * observing this KLiveData.
   * While KLiveData has one of such observers, it will be considered
   * as active.
   *
   *
   * If the observer was already added with an owner to this KLiveData, KLiveData throws an
   * [IllegalArgumentException].
   *
   * @param observer The observer that will receive the events
   */
  @MainThread
  override fun observeForever(observer: Observer<T>) {
    assertMainThread("observeForever")
    val wrapper = AlwaysActiveObserver(observer)
    val existing: ObserverWrapper? = observers.putIfAbsent(observer, wrapper)
    if (existing is LifecycleBoundObserver) {
      throw IllegalArgumentException("Cannot add the same observer with different lifecycles")
    }
    if (existing != null) {
      return
    }
    wrapper.activeStateChanged(true)
  }

  /**
   * Removes the given observer from the observers list.
   *
   * @param observer The Observer to receive events.
   */
  @MainThread
  override fun removeObserver(observer: Observer<T>) {
    assertMainThread("removeObserver")
    val removed: ObserverWrapper = observers.remove(observer) ?: return
    removed.detachObserver()
    removed.activeStateChanged(false)
  }

  /**
   * Removes all observers that are tied to the given [LifecycleOwner].
   *
   * @param owner The `LifecycleOwner` scope for the observers to be removed.
   */
  @MainThread
  override fun removeObservers(owner: LifecycleOwner) {
    assertMainThread("removeObservers")
    for ((observer, wrapper) in observers) {
      if (wrapper.isAttachedTo(owner)) {
        removeObserver(observer)
      }
    }
  }

  /**
   * Posts a task to a main thread to set the given value. So if you have a following code
   * executed in the main thread:
   * ```
   * liveData.postValue("a");
   * liveData.setValue("b");
   * ```
   * The value "b" would be set at first and later the main thread would override it with
   * the value "a".
   *
   * If you called this method multiple times before a main thread executed a posted task, only
   * the last value would be dispatched.
   *
   * @param value The new value
   */
  protected open fun postValue(value: T) {
    val postTask: Boolean
    synchronized(dataLock) {
      postTask = pendingData is NotSet
      pendingData = Actual(value)
    }
    if (!postTask) return

    GlobalScope.launch(Dispatchers.Main) {
      setValue(synchronized(dataLock) {
        val newValue = pendingData as Actual<T>
        pendingData = NotSet
        newValue.value
      })
    }
  }

  /**
   * Sets the value. If there are active observers, the value will be dispatched to them.
   *
   * This method must be called from the main thread. If you need set a value from a background
   * thread, you can use [postValue]
   *
   * @param value The new value
   */
  @MainThread
  protected open fun setValue(value: T) {
    assertMainThread("setValue")
    version++
    mData = Actual(value)
    dispatchingValue()
  }

  override val value: T?
    get() {
      val data = mData
      return if (data is Actual) {
        data.value
      } else {
        null
      }
    }

  /**
   * Called when the number of active observers change from 0 to 1.
   *
   * This callback can be used to know that this LiveData is being used thus should be kept
   * up to date.
   */
  protected open fun onActive() {}

  /**
   * Called when the number of active observers change from 1 to 0.
   *
   * This does not mean that there are no observers left, there may still be observers but their
   * lifecycle states aren't [Lifecycle.State.STARTED] or [Lifecycle.State.RESUMED]
   * (like an Activity in the back stack).
   *
   * You can check if there are observers via [hasObservers].
   */
  protected open fun onInactive() {}

  override val hasObservers: Boolean
    get() = observers.size() > 0

  override val hasActiveObservers: Boolean
    get() = activeCount > 0

  @MainThread
  internal fun changeActiveCounter(change: Int) {
    var previousActiveCount: Int = activeCount
    activeCount += change
    if (changingActiveState) {
      return
    }
    changingActiveState = true
    try {
      while (previousActiveCount != activeCount) {
        val needToCallActive = previousActiveCount == 0 && activeCount > 0
        val needToCallInactive = previousActiveCount > 0 && activeCount == 0
        previousActiveCount = activeCount
        if (needToCallActive) {
          onActive()
        } else if (needToCallInactive) {
          onInactive()
        }
      }
    } finally {
      changingActiveState = false
    }
  }

  private abstract inner class ObserverWrapper(val observer: Observer<T>) {
    var active: Boolean = false
    var lastVersion = START_VERSION

    abstract val shouldBeActive: Boolean

    open fun isAttachedTo(owner: LifecycleOwner): Boolean = false
    open fun detachObserver() {}
    open fun activeStateChanged(newActive: Boolean) {
      if (newActive == active) return

      // immediately set active state, so we'd never dispatch anything to inactive
      // owner
      active = newActive
      changeActiveCounter(if (active) 1 else -1)
      if (active) {
        dispatchingValue(this)
      }
    }
  }

  private inner class LifecycleBoundObserver(val owner: LifecycleOwner, observer: Observer<T>) :
      ObserverWrapper(observer), LifecycleEventObserver {
    override val shouldBeActive: Boolean
      get() = owner.lifecycle.currentState.isAtLeast(STARTED)

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
      var currentState = owner.lifecycle.currentState
      if (currentState == DESTROYED) {
        removeObserver(observer)
        return
      }
      var prevState: Lifecycle.State? = null
      while (prevState != currentState) {
        prevState = currentState
        activeStateChanged(shouldBeActive)
        currentState = owner.lifecycle.currentState
      }
    }

    override fun isAttachedTo(owner: LifecycleOwner) = this.owner == owner

    override fun detachObserver() = owner.lifecycle.removeObserver(this)
  }

  private inner class AlwaysActiveObserver(observer: Observer<T>) : ObserverWrapper(observer) {
    override val shouldBeActive: Boolean = true
  }

  public companion object {
    internal const val START_VERSION = -1

    private fun assertMainThread(methodName: String) {
      if (Looper.myLooper() !== Looper.getMainLooper()) {
        throw IllegalStateException("Cannot invoke $methodName on a background thread")
      }
    }
  }
}
