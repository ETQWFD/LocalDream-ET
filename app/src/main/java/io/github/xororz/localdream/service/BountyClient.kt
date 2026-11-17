package io.github.xororz.localdream.service

import android.content.Context
import io.github.xororz.localdream.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * et.219 悬赏：发布者先托管额度；提交物用发布者 RSA 公钥 + AES-GCM 混合加密，
 * 只有发布者设备上的私钥能解开，仓库里拿到 JSON 也看不到内容。
 */
object BountyClient {
    private const val API = "https://api.github.com/repos/ETQWFD/NightmareRoad-ET-Gallery/contents"
    private const val PREFS = "bounty_keys"
    private const val KEY_PRIVATE = "private_pkcs8"

    private fun conn(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 20000
            setRequestProperty("Authorization", "token ${BuildConfig.GALLERY_TOKEN}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NightmareRoad-ET")
        }

    fun publicKeyBase64(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pub = sp.getString("public_x509", null)
        val priv = sp.getString(KEY_PRIVATE, null)
        if (pub != null && priv != null) return pub
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = gen.generateKeyPair()
        sp.edit()
            .putString(KEY_PRIVATE, Base64.getEncoder().encodeToString(kp.private.encoded))
            .putString("public_x509", Base64.getEncoder().encodeToString(kp.public.encoded))
            .apply()
        return Base64.getEncoder().encodeToString(kp.public.encoded)
    }

    private fun privateKey(b64: String): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)))

    private fun publicKey(b64: String): PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))

    /** 发布悬赏：先扣额度托管，再写入 bounties/{creator}/{id}.json（带发布者公钥）。 */
    suspend fun create(ctx: Context, creator: String, title: String, amount: Long): Pair<Boolean, String> {
        val (ok, err, _) = createWithId(ctx, creator, title, amount)
        return ok to err
    }

    suspend fun createWithId(ctx: Context, creator: String, title: String, amount: Long): Triple<Boolean, String, String> =
        withContext(Dispatchers.IO) {
            if (creator.isBlank() || title.isBlank() || amount <= 0L) return@withContext Triple(false, "请填写目的和额度", "")
            if (QuotaManager.remaining(ctx) < amount) return@withContext Triple(false, "额度不足", "")
            QuotaManager.consume(ctx, amount)
            val id = "${System.currentTimeMillis()}"
            val json = JSONObject()
                .put("id", id).put("creator", creator).put("title", title)
                .put("amount", amount).put("ts", System.currentTimeMillis())
                .put("status", "open").put("pub", publicKeyBase64(ctx))
            val (ok, err) = writeRaw("bounties/$creator/$id.json", json)
            if (!ok) {
                QuotaManager.refund(ctx, amount)
            } else {
                // et.243: 同步进索引，别人马上能在悬赏页看到（旧版要等目录遍历，
                // 慢到看起来像“发布失败”）。
                runCatching { patchBountyIndex(json, "open") }
            }
            Triple(ok, if (ok) "" else err, id)
        }

    private fun writeRaw(path: String, json: JSONObject): Pair<Boolean, String> = runCatching {
        val c = conn("$API/$path", "PUT").apply { doOutput = true }
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("message", "bounty $path")
            .put("content", Base64.getEncoder().encodeToString(json.toString().toByteArray()))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code in 200..201) return@runCatching true to ""
        val detail = runCatching { c.errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
        false to "HTTP $code ${detail.take(80)}"
    }.getOrDefault(false to "网络错误")

    /** 列出所有悬赏：优先读索引，索引缺失时回落到逐目录遍历。 */
    suspend fun list(): List<JSONObject> = withContext(Dispatchers.IO) {
        val viaIndex = runCatching { fetchBountyIndex() }.getOrNull()
        if (viaIndex != null && viaIndex.isNotEmpty()) {
            return@withContext viaIndex
                .filter { it.optString("status", "open") == "open" }
                .map { JSONObject(it.toString()).put("path", it.optString("path", "")) }
                .sortedByDescending { it.optLong("ts") }
        }
        val out = mutableListOf<JSONObject>()
        runCatching {
            val root = conn("$API/bounties")
            if (root.responseCode !in 200..299) return@runCatching
            val dirs = JSONArray(root.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            for (i in 0 until dirs.length()) {
                val d = dirs.getJSONObject(i)
                if (d.optString("type") != "dir") continue
                val creator = d.optString("name")
                val list = conn("$API/bounties/$creator").inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val arr = JSONArray(list)
                for (j in 0 until arr.length()) {
                    val f = arr.getJSONObject(j)
                    if (!f.optString("name").endsWith(".json")) continue
                    val url = f.optString("url")
                    val js = conn(url).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val rootObj = JSONObject(js)
                    out += JSONObject(
                        String(Base64.getDecoder().decode(rootObj.optString("content").replace("\n", "")), Charsets.UTF_8),
                    ).put("path", f.optString("path")).put("sha", f.optString("sha"))
                }
            }
        }
        out.filter { it.optString("status", "open") == "open" }
            .sortedByDescending { it.optLong("ts") }
    }

    /** 提交成果：AES 随机密钥加密内容，再用发布者公钥包住 AES 密钥。 */
    suspend fun submit(bounty: JSONObject, submitter: String, fileName: String, bytes: ByteArray): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pub = publicKey(bounty.optString("pub"))
                val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, aes)
                val iv = cipher.iv
                val data = cipher.doFinal(bytes)
                val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                rsa.init(Cipher.ENCRYPT_MODE, pub)
                val keyEnc = rsa.doFinal(aes.encoded)
                val creator = bounty.optString("creator")
                val id = bounty.optString("id")
                val name = "${System.currentTimeMillis()}_${submitter.filter { it.isLetterOrDigit() }}.json"
                val json = JSONObject()
                    .put("submitter", submitter).put("name", fileName)
                    .put("key", Base64.getEncoder().encodeToString(keyEnc))
                    .put("iv", Base64.getEncoder().encodeToString(iv))
                    .put("data", Base64.getEncoder().encodeToString(data))
                    .put("ts", System.currentTimeMillis())
                val (ok, err) = writeRaw("bounties/$creator/$id/subs/$name", json)
                ok to if (ok) "" else err
            }.getOrElse { false to (it.message ?: "提交失败") }
        }

    /** 只有发布者能解开：RSA 私钥解 AES 密钥，再 AES-GCM 解密。 */
    fun decrypt(ctx: Context, subJson: JSONObject): ByteArray {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val privB64 = sp.getString(KEY_PRIVATE, null) ?: throw IllegalStateException("本机没有发布者私钥")
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(Cipher.DECRYPT_MODE, privateKey(privB64))
        val aesKey: SecretKey = SecretKeySpec(
            rsa.doFinal(Base64.getDecoder().decode(subJson.optString("key"))), "AES",
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, Base64.getDecoder().decode(subJson.optString("iv"))))
        return cipher.doFinal(Base64.getDecoder().decode(subJson.optString("data")))
    }

    suspend fun listSubs(bounty: JSONObject): List<JSONObject> = withContext(Dispatchers.IO) {
        val out = mutableListOf<JSONObject>()
        runCatching {
            val creator = bounty.optString("creator")
            val id = bounty.optString("id")
            val c = conn("$API/bounties/$creator/$id/subs")
            if (c.responseCode !in 200..299) return@runCatching
            val arr = JSONArray(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (!f.optString("name").endsWith(".json")) continue
                val r = conn(f.optString("url")).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val root = JSONObject(r)
                out += JSONObject(
                    String(Base64.getDecoder().decode(root.optString("content").replace("\n", "")), Charsets.UTF_8),
                ).put("_path", f.optString("path")).put("_sha", f.optString("sha"))
            }
        }
        out.sortedByDescending { it.optLong("ts") }
    }

    private fun putWithSha(path: String, sha: String, json: JSONObject): Boolean = runCatching {
        val c = conn("$API/$path", "PUT").apply { doOutput = true }
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("message", "update $path").put("sha", sha)
            .put("content", Base64.getEncoder().encodeToString(json.toString().toByteArray()))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        c.responseCode in 200..201
    }.getOrDefault(false)

    /** 驳回某个提交：写 rejected；驳回原因会随提交记录展示，发布者仍需重新验收。 */
    suspend fun reject(sub: JSONObject, reason: String): Boolean = withContext(Dispatchers.IO) {
        val path = sub.optString("_path")
        val sha = sub.optString("_sha")
        if (path.isBlank() || sha.isBlank()) return@withContext false
        val plain = JSONObject(sub.toString())
        plain.remove("_path"); plain.remove("_sha")
        plain.put("status", "rejected").put("reason", reason)
            .put("rejectedTs", System.currentTimeMillis())
        putWithSha(path, sha, plain)
    }

    /** 关闭悬赏（paid / rejected / expired），避免重复结算。 */
    suspend fun closeBounty(bounty: JSONObject, status: String): Boolean = withContext(Dispatchers.IO) {
        val path = bounty.optString("path")
        var sha = bounty.optString("sha")
        if (path.isBlank()) return@withContext false
        // et.243：列表改走索引后不再带 sha，关闭前补一次 HEAD 读取，
        // 否则“完成后悬赏不消失”就是因为这里直接 return false。
        if (sha.isBlank()) {
            sha = runCatching {
                val c = conn("$API/$path")
                if (c.responseCode !in 200..299) return@runCatching ""
                JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                    .optString("sha", "")
            }.getOrDefault("")
        }
        if (sha.isBlank()) return@withContext false
        val plain = JSONObject(bounty.toString())
        plain.remove("path"); plain.remove("sha")
        plain.put("status", status).put("closedTs", System.currentTimeMillis())
        val ok = putWithSha(path, sha, plain)
        if (ok) patchBountyIndex(plain, status)
        ok
    }

    /**
     * et.243: 悬赏索引 bounties/index.json。
     * 旧实现要遍历“每个发布者目录 + 每个文件”三次请求，人多以后悬赏页转圈几十秒，
     * 表现就是“发不了悬赏、提交不了、结算了还挂在列表上”。现在一次请求读全量，
     * 发布/结算时同步更新索引，已结束（paid/closed/rejected）不再出现在列表里。
     */
    private const val BOUNTY_INDEX = "bounties/index.json"

    private fun fetchBountyIndex(): List<JSONObject>? = runCatching {
        val raw = listOf(
            "https://cdn.jsdelivr.net/gh/ETQWFD/NightmareRoad-ET-Gallery@main/$BOUNTY_INDEX",
            "https://raw.gitmirror.com/ETQWFD/NightmareRoad-ET-Gallery/main/$BOUNTY_INDEX",
            "https://raw.githubusercontent.com/ETQWFD/NightmareRoad-ET-Gallery/main/$BOUNTY_INDEX",
        )
        for (u in raw) {
            val c = conn(u)
            if (c.responseCode !in 200..299) continue
            val arr = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                .optJSONArray("items") ?: return@runCatching null
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
        }
        val c = conn("$API/$BOUNTY_INDEX")
        if (c.responseCode !in 200..299) return@runCatching null
        val obj = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        val b64 = obj.optString("content", "").replace("\n", "")
        if (b64.isBlank()) return@runCatching null
        val arr = JSONObject(String(Base64.getDecoder().decode(b64), Charsets.UTF_8)).optJSONArray("items")
            ?: return@runCatching null
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
    }.getOrNull()

    private fun patchBountyIndex(entry: JSONObject, status: String): Boolean = runCatching {
        // 必须以 API 上的最新内容为基准：jsDelivr 索引缓存有几分钟延迟，
        // 基于过期索引回写会把别人刚发布的悬赏冲掉。
        var sha = ""
        val cur = mutableListOf<JSONObject>()
        runCatching {
            val c = conn("$API/$BOUNTY_INDEX")
            if (c.responseCode in 200..299) {
                val obj = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                sha = obj.optString("sha", "")
                val b64 = obj.optString("content", "").replace("\n", "")
                if (b64.isNotBlank()) {
                    val arr = JSONObject(String(Base64.getDecoder().decode(b64), Charsets.UTF_8))
                        .optJSONArray("items")
                    if (arr != null) {
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { cur.add(it) }
                    }
                }
            }
        }
        val id = entry.optLong("id", entry.optInt("id", 0).toLong())
        val item = JSONObject(entry.toString())
        item.remove("path"); item.remove("sha")
        item.put("path", "bounties/${entry.optString("creator")}/$id.json")
        if (status.isNotBlank()) item.put("status", status)
        val idx = cur.indexOfFirst { it.optString("path") == item.optString("path") }
        if (idx >= 0) cur[idx] = item else cur.add(item)
        val arr = JSONArray()
        cur.filter { it.optString("status", "open") == "open" || System.currentTimeMillis() - it.optLong("ts") < 7 * 86400_000L }
            .sortedByDescending { it.optLong("ts") }.take(500).forEach { arr.put(it) }
        val body = JSONObject().put("v", 1).put("updated", System.currentTimeMillis()).put("items", arr)
        val payload = JSONObject().put("message", "bounty index")
            .put("content", Base64.getEncoder().encodeToString(body.toString().toByteArray()))
        if (sha.isNotBlank()) payload.put("sha", sha)
        val c = conn("$API/$BOUNTY_INDEX", "PUT").apply { doOutput = true }
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(payload.toString().toByteArray()) }
        val ok = c.responseCode in 200..201
        if (ok) io.github.xororz.localdream.net.MirrorPurge.jsdelivr("NightmareRoad-ET-Gallery", BOUNTY_INDEX)
        ok
    }.getOrDefault(false)

    /**
     * et.240：24 小时自动结算。原来只扫描“当前用户自己发布”的悬赏，别人打开
     * 悬赏页时永远不会触发，托管额度就一直卡住。现在任何用户打开悬赏列表都会
     * 扫描全部超过 24 小时仍 open 的悬赏：默认发布者“满意”，把托管额度付给
     * 最早一位未被驳回的提交者，并给双方各写一条公告。
     */
    suspend fun autoSettle(ctx: Context, creator: String, list: List<JSONObject>): Int = withContext(Dispatchers.IO) {
        var settled = 0
        for (b in list) {
            val bountyCreator = b.optString("creator")
            if (bountyCreator.isBlank()) continue
            val st = b.optString("status")
            if (st == "paid" || st == "closed" || st == "rejected") continue
            val ageH = (System.currentTimeMillis() - b.optLong("ts")) / 3_600_000L
            if (ageH < 24L) continue
            val subs = runCatching { listSubs(b) }.getOrDefault(emptyList())
            val target = subs.firstOrNull { it.optString("status") != "rejected" } ?: continue
            val to = target.optString("submitter")
            if (to.isBlank()) continue
            val amount = b.optLong("amount")
            if (amount <= 0L) continue
            val ok = GalleryClient.sendQuota(bountyCreator, to, amount).first
            if (ok) {
                closeBounty(b, "paid")
                runCatching {
                    NoticesClient.post(
                        to, "悬赏自动支付",
                        "「${b.optString("title")}」超过 24 小时未验收，系统默认发布者满意，$amount 额度已到账。",
                    )
                }
                runCatching {
                    NoticesClient.post(
                        bountyCreator, "悬赏已自动结算",
                        "「${b.optString("title")}」超过 24 小时未确认，已向 @$to 支付 $amount 额度。如需驳回请在 24 小时内操作。",
                    )
                }
                settled++
            }
        }
        settled
    }

    /** 验收后把托管额度转给提交者；托管时已扣过，这里不再扣。 */
    suspend fun pay(from: String, to: String, amount: Long): Boolean =
        GalleryClient.sendQuota(from, to, amount).first
}
