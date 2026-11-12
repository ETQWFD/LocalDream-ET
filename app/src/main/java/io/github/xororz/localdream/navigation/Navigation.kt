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

    object Edit : Screen(
        "edit/{modelId}?modelName={modelName}&backendType={backendType}&imagePath={imagePath}",
    ) {
        /**
         * [imagePath] is an optional absolute path of an already generated image
         * (written by the result page into cacheDir). When present the 修图 page
         * opens with that picture loaded instead of asking the user to pick it
         * again from the album.
         */
        fun createRoute(
            modelId: String,
            modelName: String,
            backendType: String,
            imagePath: String? = null,
        ): String {
            val base = "edit/$modelId?modelName=${android.net.Uri.encode(modelName)}" +
                "&backendType=${android.net.Uri.encode(backendType)}"
            return if (imagePath.isNullOrBlank()) {
                base
            } else {
                "$base&imagePath=${android.net.Uri.encode(imagePath)}"
            }
        }
    }

    object History : Screen("history")

    object RemoteLink : Screen("remote_link")

    object Gallery : Screen("gallery")
}
