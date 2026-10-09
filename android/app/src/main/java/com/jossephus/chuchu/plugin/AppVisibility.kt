package com.jossephus.chuchu.plugin

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether chuchu has a started activity, for plugins ([PluginHost.appVisible]). Counted from
 * activity callbacks rather than lifecycle-process so it adds no dependency.
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun install(application: Application) = application.registerActivityLifecycleCallbacks(this)

    override fun onActivityStarted(activity: Activity) {
        started++
        _visible.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        _visible.value = started > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
