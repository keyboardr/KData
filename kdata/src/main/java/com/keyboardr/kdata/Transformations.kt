package com.keyboardr.kdata

public fun <X, Y> KData<X>.map(mapFunction: (X) -> Y): KData<Y> =
    MediatorKLiveData<Y>().apply {
      addSource(this@map) {
        setValue(mapFunction(it))
      }
    }

public fun <X, Y> KData<X>.switchMap(mapFunction: (X) -> KData<Y>): KData<Y> =
    MediatorKLiveData<Y>().apply {
      addSource(this@switchMap, object : Observer<X> {
        var source: KData<Y>? = null

        override fun onChanged(value: X) {
          val newKData = mapFunction(value)
          if (source === newKData) return
          source?.let { removeSource(it) }
          source = newKData
          source?.let {
            addSource(it) { value ->
              setValue(value)
            }
          }
        }
      })
    }

public fun <X> KData<X>.distinctUntilChanged(): KData<X> =
    MediatorKLiveData<X>().apply {
      addSource(this@distinctUntilChanged, object : Observer<X> {
        var firstTime = true

        override fun onChanged(value: X) {
          val previousValue = this@apply.value
          if (firstTime || previousValue != value) {
            firstTime = false
            setValue(value)
          }
        }
      })
    }