package io.legado.app.help.watchparty

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.legado.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * 仅供测试：用 adb 驱动「一起看」的建/加/批/退、以及改倍速，
 * 不再靠点屏幕、也不用往输入框打字（中文输入法会把密码里的字母吃掉）。
 *
 * 两种入口，都只在 -PpartyTestHooks=true 打包时可用；正式包里都是关的：
 * 1) Service（推荐，Service 无界面，不会把播放页顶到后台；MIUI 又会卡广播队列）：
 *      adb shell am startservice -n com.mie.reading/io.legado.app.help.watchparty.PartyTestService \
 *          --es cmd create
 *    结果写到 /sdcard/Android/data/com.mie.reading/files/party_test.txt，adb 直接 cat：
 *      adb shell cat /sdcard/Android/data/com.mie.reading/files/party_test.txt
 * 2) 广播（部分设备可用，需显式组件 + FLAG_INCLUDE_STOPPED_PACKAGES）：
 *      adb shell am broadcast -a com.mie.reading.PARTY_TEST -f 0x01000020 \
 *          -n com.mie.reading/.help.watchparty.PartyTestReceiver --es cmd create
 */
object PartyTestBus {
    /** 播放页里收到就立刻改本地倍速，等价于点倍速按钮 */
    val speed = MutableStateFlow<Float?>(null)
}

object PartyTestCommands {

    fun log(msg: String) = Log.d("PartyTest", msg)

    /** 执行一条命令，返回一行结果文本 */
    fun run(intent: Intent): String {
        val cmd = intent.getStringExtra("cmd").orEmpty()
        log("收到 cmd=$cmd")
        val out = runCatching {
            when (cmd) {
                "create" -> {
                    val r = WatchPartySession.createRoom()
                    "create invite=${r.getOrNull()} err=${r.exceptionOrNull()?.message}"
                }

                "join" -> {
                    val invite = intent.getStringExtra("invite").orEmpty()
                    val r = WatchPartySession.joinRoom(invite)
                    "join=$invite ok=${r.isSuccess} my=${r.getOrNull()} err=${r.exceptionOrNull()?.message}"
                }

                "approve" -> {
                    val uid = intent.getStringExtra("userId")
                        ?: WatchPartySession.state.value.pending.firstOrNull()?.userId
                    if (uid == null) "approve=none" else {
                        WatchPartySession.approve(uid)
                        "approve=$uid"
                    }
                }

                "leave" -> {
                    WatchPartySession.leaveAndForget()
                    "leave=ok"
                }

                "speed" -> {
                    val v = intent.getStringExtra("value")?.toFloatOrNull()
                    if (v == null) "speed=bad" else {
                        PartyTestBus.speed.value = v
                        "speed=$v"
                    }
                }

                "info" -> info()

                // 指定 fid 直接进入播放页：pendingPlayFid 是现有机制，库扫描完成后会自动开播
                // at=0 时先把这一集的续播进度清掉，保证从头起播（排障/回归专用）
                "play" -> {
                    val repo = org.koin.core.context.GlobalContext.get()
                        .get<io.legado.app.data.repository.VideoSettingsRepository>()
                    val fid = intent.getStringExtra("fid").orEmpty()
                    if (fid.isBlank()) "play fid=missing" else {
                        val at = intent.getStringExtra("at").orEmpty()
                        if (at == "0") {
                            runCatching {
                                val json = org.json.JSONObject(repo.videoProgressData)
                                json.remove(fid)
                                repo.videoProgressData = json.toString()
                            }
                        }
                        repo.pendingPlayFid = fid
                        "play fid=$fid at=${if (at == "0") "0" else "keep"} pending=${repo.pendingPlayFid}"
                    }
                }

                // 片头片尾存储的探针：dump / clear / set
                "intro" -> {
                    val repo = org.koin.core.context.GlobalContext.get()
                        .get<io.legado.app.data.repository.VideoSettingsRepository>()
                    val fid = intent.getStringExtra("fid").orEmpty()
                    if (fid.isBlank()) "intro fid=missing" else when (val op = intent.getStringExtra("op")) {
                        null, "dump" -> {
                            val v = io.legado.app.help.video.introoutro.EpisodeIntroOutroStore.read(repo, fid)
                            if (v == null) "intro fid=$fid none"
                            else "intro fid=$fid introEnd=${v.introEndMs} outroStart=${v.outroStartMs} " +
                                "src=${v.source} conf=${v.confidence}"
                        }
                        "clear" -> {
                            io.legado.app.help.video.introoutro.EpisodeIntroOutroStore.remove(repo, fid)
                            val v = io.legado.app.help.video.introoutro.EpisodeIntroOutroStore.read(repo, fid)
                            "intro clear fid=$fid now=${if (v == null) "none" else "introEnd=${v.introEndMs}"}"
                        }
                        else -> "intro bad op=$op"
                    }
                }

                // 音频特征库：测试用一次性清空（正式版靠版本号自愈，不需要这个）
                "audio" -> {
                    val op = intent.getStringExtra("op").orEmpty()
                    if (op == "clear") {
                        "audio clear 删除 ${io.legado.app.help.video.introoutro.EpisodeAudioStore.clearAll()} 个文件"
                    } else "audio bad op=$op"
                }

                else -> "unknown cmd=$cmd"
            }
        }.getOrElse { "error=${it.javaClass.simpleName}:${it.message}" }
        log("result=$out")
        return out
    }

    /** 当前房间状态一行摘要 */
    fun info(): String {
        val s = WatchPartySession.state.value
        return "active=${s.active} host=${s.isHost} conn=${s.connected} appr=${s.approved} " +
            "code=${s.code} hostLeft=${s.hostLeft} err=${s.error} " +
            "members=${s.members.map { it.userName }} pending=${s.pending.map { it.userName }} " +
            "speed=${s.remoteSync?.speed} pos=${s.remoteSync?.positionMs} " +
            "show=${s.show?.fid}:${s.show?.episode}"
    }

    /** 把结果写到外部私有目录，免 root 也能 adb cat */
    fun writeResult(context: Context, text: String) {
        runCatching {
            File(context.getExternalFilesDir(null), "party_test.txt").writeText(text)
        }.onFailure { log("写结果失败: ${it.message}") }
    }
}

class PartyTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.TEST_HOOKS) {
            PartyTestCommands.log("TEST_HOOKS 未开启，忽略")
            return
        }
        val out = PartyTestCommands.run(intent)
        PartyTestCommands.writeResult(context, out)
        runCatching { setResultData(out) }
    }
}

class PartyTestService : android.app.Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.TEST_HOOKS) {
            PartyTestCommands.log("TEST_HOOKS 未开启，忽略")
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.let {
            val out = PartyTestCommands.run(it)
            PartyTestCommands.writeResult(this, out)
        }
        stopSelf()
        return START_NOT_STICKY
    }
}
