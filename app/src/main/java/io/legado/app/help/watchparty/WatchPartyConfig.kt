package io.legado.app.help.watchparty

import android.content.Context
import splitties.init.appCtx

/**
 * 「一起看」的 Supabase 连接配置。
 *
 * 单独放在一个 SharedPreferences 里，不进 AppConfig：
 * 1. anon key 属于凭据，本来就不该随备份包走；
 * 2. 这些值只有影视一起看用得上，塞进全局 settings 只会让备份更脏。
 */
object WatchPartyConfig {

    private const val PREF_NAME = "watch_party"

    /** 内置默认项目地址与 anon key（公开密钥，可在面板里覆盖） */
    private const val DEFAULT_URL = "https://dnfjbgimicdrslkvaqbn.supabase.co"
    private const val DEFAULT_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRuZmppYmdtaWNkcnNsa3ZhcWJuIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTUxNjY3NzUsImV4cCI6MjExMDc0Mjc3NX0.p_cHyFwnrK7kQV2bCJCShRIfUvjgXncCex7tuaMZVaI"

    private fun pref() = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 项目地址，形如 https://xxxx.supabase.co */
    var url: String
        get() = pref().getString("url", DEFAULT_URL).orEmpty().ifBlank { DEFAULT_URL }
        set(value) = pref().edit().putString("url", value.trim()).apply()

    /** publishable / anon key */
    var apiKey: String
        get() = pref().getString("api_key", DEFAULT_KEY).orEmpty().ifBlank { DEFAULT_KEY }
        set(value) = pref().edit().putString("api_key", value.trim()).apply()

    /** 本机昵称，默认取设置里的设备名 */
    var displayName: String
        get() = pref().getString("display_name", "") ?: ""
        set(value) = pref().edit().putString("display_name", value.trim()).apply()

    val isReady: Boolean get() = url.isNotBlank() && apiKey.isNotBlank()

    fun nameOrDefault(): String {
        val n = displayName
        if (n.isNotBlank()) return n
        val device = android.os.Build.MODEL ?: ""
        return if (device.isBlank()) "我" else device
    }
}
