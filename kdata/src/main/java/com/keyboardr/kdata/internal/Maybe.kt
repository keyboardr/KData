package com.keyboardr.kdata.internal

internal sealed class Maybe<out T>

internal object NotSet : Maybe<Nothing>()

internal data class Actual<T>(val value: T) : Maybe<T>()
