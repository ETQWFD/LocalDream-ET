package io.github.xororz.localdream.cloud

/**
 * Cloud backup config (et.25 PAT revision). OAuth authorization-code flow was
 * dropped: the user pastes a Personal Access Token (PAT) which we validate
 * against the GitHub/Gitee user API. There are NO baked-in client secrets, no
 * OAuth redirect, and no automatic token minting. The owner comes from the
 * validated login; the private backup repo name is fixed below.
 */
object CloudConfig {
    enum class Provider { GITHUB, GITEE }

    /** Fixed private backup repo name (exact casing required). */
    const val BACKUP_REPO = "LocalDreamET-Backup"

    /**
     * Update mirror channel (et.25). GitHub is the authoritative/primary channel;
     * Gitee is an optional mirror. The Gitee owner/repo below are placeholders —
     * edit them to the real account once the user creates the mirror. A missing
     * / unreachable Gitee channel must silently fall back to GitHub.
     */
    const val GITEE_OWNER = "LocalDream-ET"
    const val GITEE_REPO = "LocalDream-ET"

    val githubUpdateJson: String
        get() = "https://etqwfd.github.io/LocalDream-ET/update.json"
    val githubUpdateJsonRaw: String
        get() = "https://raw.githubusercontent.com/ETQWFD/LocalDream-ET/main/docs/update.json"

    /** Gitee Pages/raw update.json; replace owner/repo once the mirror exists. */
    val giteeUpdateJson: String
        get() = "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/raw/main/docs/update.json"
}
