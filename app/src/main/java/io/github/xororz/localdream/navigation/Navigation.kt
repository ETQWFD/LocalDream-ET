package io.github.xororz.localdream.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController

// Ignores pops while a pop transition is already running (current entry not RESUMED),
// so rapid back-button taps cannot pop the start destination and blank the NavHost.
fun NavController.popBackStackIfResumed(): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) {
        return false
    }
    return popBackStack()
}

sealed class Screen(val route: String) {
    object ModelList : Screen("model_list")
    object ModelRun : Screen("model_run/{modelId}?remote={remote}") {
        fun createRoute(modelId: String, remote: Boolean = false) = "model_run/$modelId?remote=$remote"
    }

    object Upscale : Screen("upscale")

    object Edit : Screen("edit/{modelId}?modelName={modelName}&backendType={backendType}") {
        fun createRoute(modelId: String, modelName: String, backendType: String) =
            "edit/$modelId?modelName=$modelName&backendType=$backendType"
    }

    object History : Screen("history")

    object RemoteLink : Screen("remote_link")
}
