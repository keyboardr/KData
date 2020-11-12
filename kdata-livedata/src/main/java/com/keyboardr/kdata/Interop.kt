package com.keyboardr.kdata

import androidx.lifecycle.LiveData

public fun <T> KData<T>.toLiveData(): LiveData<T> = KDataLiveData(this)

internal class KDataLiveData<T>(private val source: KData<T>) : LiveData<T>() {
    private val observer = Observer<T> { value -> this@KDataLiveData.value = value }

    override fun onActive() {
        source.observeForever(observer)
    }

    override fun onInactive() {
        source.removeObserver(observer)
    }
}

public fun <T> LiveData<T>.toKData(): KData<T> = LiveDataKData(this)

internal class LiveDataKData<T>(private val source: LiveData<T>) : KLiveData<T>() {
    private val observer = androidx.lifecycle.Observer<T> { value -> setValue(value) }

    override fun onActive() {
        source.observeForever(observer)
    }

    override fun onInactive() {
        source.removeObserver(observer)
    }

}
