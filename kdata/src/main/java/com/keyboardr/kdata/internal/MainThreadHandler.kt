package com.keyboardr.kdata.internal

import android.os.Handler
import android.os.Looper


internal object MainThreadHandler : Handler(Looper.getMainLooper()) {

}