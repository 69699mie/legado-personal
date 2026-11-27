package io.legado.app.help.watchparty

import java.util.concurrent.ConcurrentHashMap

/**
 * 只接受 sentAt 比上一次更大的一条 sync。
 * 中继的轮询在 since 失效时会重放历史消息，重放的旧 sync 会把倍速/暂停改回旧值，
 * 表现为两端「倍速不同步」；这里按 userId 单调过滤掉旧包。
 */
internal class SyncFreshness {
    private val seen = ConcurrentHashMap<String, Long>()

    fun accept(userId: String, sentAt: Long): Boolean {
        if (userId.isBlank() || sentAt <= 0L) return false
        var accepted = false
        seen.compute(userId) { _, prev ->
            if (prev == null || sentAt > prev) {
                accepted = true
                sentAt
            } else {
                prev
            }
        }
        return accepted
    }

    fun forget(userId: String) {
        seen.remove(userId)
    }
}
