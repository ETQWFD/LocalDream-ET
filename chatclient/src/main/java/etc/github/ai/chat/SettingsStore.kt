package etc.github.ai.chat

import android.content.Context

/**
 * Local, on-device settings. Persisted with SharedPreferences (no network,
 * no logs of secrets). All fields are editable from the settings screen.
 */
data class Settings(
    var baseUrl: String = "http://127.0.0.1:8080",
    var apiKey: String = "",
    var modelName: String = "",
    var negativePrompt: String = "",
    /** Empty = do NOT send batch_size (server injects its own default, e.g. 6). */
    var batchSize: String = "",
    var githubToken: String = "",
    /** owner/repo, empty = cloud upload button hidden. */
    var githubRepo: String = "",
)

class SettingsStore(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("et_chat_settings", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        baseUrl = sp.getString("base_url", "http://127.0.0.1:8080")!!,
        apiKey = sp.getString("api_key", "")!!,
        modelName = sp.getString("model_name", "")!!,
        negativePrompt = sp.getString("negative_prompt", "")!!,
        batchSize = sp.getString("batch_size", "")!!,
        githubToken = sp.getString("github_token", "")!!,
        githubRepo = sp.getString("github_repo", "")!!,
    )

    fun save(s: Settings) {
        sp.edit()
            .putString("base_url", s.baseUrl.trim())
            .putString("api_key", s.apiKey.trim())
            .putString("model_name", s.modelName.trim())
            .putString("negative_prompt", s.negativePrompt.trim())
            .putString("batch_size", s.batchSize.trim())
            .putString("github_token", s.githubToken.trim())
            .putString("github_repo", s.githubRepo.trim())
            .apply()
    }
}
