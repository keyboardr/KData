package com.keyboardr.kdata

import androidx.annotation.CallSuper
import androidx.annotation.MainThread
import com.keyboardr.kdata.internal.SafeIterableMap

public open class MediatorKLiveData<T> : MutableKLiveData<T>() {

  private val sources = SafeIterableMap<KData<*>, Source<*>>()

  /**
   * Starts to listen the given [source] KData, [onChanged] observer will be called
   * when [source] value was changed.
   *
   *
   * [onChanged] callback will be called only when this [MediatorKLiveData] is active.
   *
   *  If the given KData is already added as a source but with a different Observer,
   * [IllegalArgumentException] will be thrown.
   *
   * @param source    the [KData] to listen to
   * @param onChanged The observer that will receive the events
   * @param S       The type of data hold by [source] LiveData
   */
  @MainThread
  public fun <S> addSource(source: KData<S>, onChanged: Observer<S>) {
    val e = Source(source, onChanged)
    val existing: Source<*>? = sources.putIfAbsent(source, e)
    require(!(existing != null && existing.observer !== onChanged)) {
      "This source was already added with the different observer"
    }
    if (existing != null) {
      return
    }
    if (hasActiveObservers) {
      e.plug()
    }
  }

  /**
   * Stops to listen the given [KData].
   *
   * @param toRemote [KData] to stop to listen
   */
  @MainThread
  public fun <S> removeSource(toRemote: KData<S>) {
    val source: Source<*>? = sources.remove(toRemote)
    source?.unplug()
  }

  @CallSuper
  override fun onActive() {
    for (source in sources) {
      source.value.plug()
    }
  }

  @CallSuper
  override fun onInactive() {
    for (source in sources) {
      source.value.unplug()
    }
  }

  private class Source<V>(private val data: KData<V>, val observer: Observer<V>) :
      Observer<V> {
    private var version = START_VERSION

    fun plug() {
      data.observeForever(this)
    }

    fun unplug() {
      data.removeObserver(this)
    }

    override fun onChanged(value: V) {
      if (data is KLiveData) {
        if (version != data.version) {
          version = data.version
          observer.onChanged(value)
        }
      } else {
        observer.onChanged(value)
      }
    }
  }
}