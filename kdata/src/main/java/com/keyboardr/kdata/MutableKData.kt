package com.keyboardr.kdata

public interface MutableKData<T> : KData<T> {
  public fun setValue(value: T)
  public fun postValue(value: T)
}

public open class MutableKLiveData<T> : KLiveData<T>, MutableKData<T> {

  public constructor() : super()
  public constructor(value: T) : super(value)

  public override fun postValue(value: T) {
    super.postValue(value)
  }

  public override fun setValue(value: T) {
    super.setValue(value)
  }
}