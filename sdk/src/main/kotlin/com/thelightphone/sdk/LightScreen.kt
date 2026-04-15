package com.thelightphone.sdk

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

abstract class SimpleLightScreen {
    @Composable
    abstract fun Content()

    open val showBackBar: Boolean = true

    open fun willShow() {}
    open fun willHide() {}
    open fun onAppPause() {}

    internal var activity: LightActivity? = null

    @PublishedApi
    internal fun requireActivity(): LightActivity {
        return activity
            ?: throw IllegalStateException("LightScreen is not attached to a LightActivity")
    }

    internal open fun notifyWillShow() {
        willShow()
    }

    internal open fun notifyWillHide() {
        willHide()
    }

    internal open fun notifyAppPause() {
        onAppPause()
    }

    fun navigateTo(screenFactory: () -> SimpleLightScreen) {
        val screen = screenFactory()
        screen.activity = activity
        requireActivity().navigateTo(screen)
    }

    fun goBack() {
        requireActivity().goBack()
    }
}

abstract class LightScreen<VM : LightViewModel> : SimpleLightScreen() {
    abstract val viewModelClass: Class<VM>
    abstract fun createViewModel(): VM

    @Suppress("UNCHECKED_CAST")
    val viewModel: VM by lazy {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return createViewModel() as T
            }
        }
        ViewModelProvider(requireActivity(), factory)[viewModelClass]
    }

    internal override fun notifyWillShow() {
        super.notifyWillShow()
        viewModel.onScreenShow(this)
    }

    internal override fun notifyWillHide() {
        super.notifyWillHide()
        viewModel.onScreenHide(this)
    }

    internal override fun notifyAppPause() {
        super.notifyAppPause()
        viewModel.onAppPause()
    }
}
