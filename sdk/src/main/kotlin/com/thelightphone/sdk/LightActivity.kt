package com.thelightphone.sdk

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier

class LightActivity internal constructor() : ComponentActivity() {

    private val backStack = mutableListOf<SimpleLightScreen>()
    private val currentScreen = mutableStateOf<SimpleLightScreen?>(null)

    internal fun navigateTo(screen: SimpleLightScreen) {
        currentScreen.value?.notifyWillHide()
        screen.activity = this
        backStack.add(screen)
        screen.notifyWillShow()
        currentScreen.value = screen
    }

    internal fun goBack() {
        currentScreen.value?.let {
            it.notifyWillHide()
            it.activity = null
        }
        backStack.removeAt(backStack.lastIndex)
        if (backStack.isEmpty()) {
            finish()
            return
        }
        val previous = backStack.last()
        previous.notifyWillShow()
        currentScreen.value = previous
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialClassName = packageManager
            .getActivityInfo(componentName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString("com.thelightphone.sdk.initialScreen")
            ?: throw IllegalStateException(
                "LightActivity requires meta-data 'com.thelightphone.sdk.initialScreen' " +
                "pointing to a SimpleLightScreen subclass"
            )

        val initial = try {
            Class.forName(initialClassName).getDeclaredConstructor().newInstance() as SimpleLightScreen
        } catch (e: Exception) {
            throw IllegalStateException("Could not instantiate initial screen: $initialClassName", e)
        }

        initial.activity = this
        backStack.add(initial)
        initial.notifyWillShow()
        currentScreen.value = initial

        setContent {
            val screen = currentScreen.value
            if (screen != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        screen.Content()
                    }
                    if (screen.showBackBar) {
                        LightBackBar(onBack = ::goBack)
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goBack()
                }
            }
        )
    }

    override fun onPause() {
        super.onPause()
        currentScreen.value?.notifyAppPause()
    }

    override fun onResume() {
        super.onResume()
        currentScreen.value?.notifyWillShow()
    }
}
