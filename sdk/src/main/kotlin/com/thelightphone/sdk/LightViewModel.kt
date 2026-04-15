package com.thelightphone.sdk

import androidx.lifecycle.ViewModel

abstract class LightViewModel : ViewModel() {
    open fun onScreenShow(screen: SimpleLightScreen) {}
    open fun onScreenHide(screen: SimpleLightScreen) {}
    open fun onAppPause() {}
}
