package io.legado.app.help.watchparty

/** 播放状态广播体 */
data class PartySyncState(
    val userId: String = "",
    val userName: String = "",
    val fid: String = "",
    val episode: String = "",
    val positionMs: Long = 0L,
    val paused: Boolean = true,
    /** 倍速：两端都可改，按「最后操作生效」同步 */
    val speed: Float = 1.0f,
    /** 发送端本地时间戳（用于估算传输耗时，做粗略漂移校正） */
    val sentAt: Long = 0L,
)

/** 房间成员 */
data class PartyMember(
    val userId: String,
    val userName: String,
    val lastSeen: Long = 0L,
    val isHost: Boolean = false,
)

/** 一起看会话对外的状态 */
data class WatchPartyState(
    val active: Boolean = false,
    val code: String = "",
    /** 房间密码：房主端用来展示分享，客机端仅用于解密 */
    val password: String = "",
    val isHost: Boolean = false,
    /** 房主天然为 true；客机要等房主同意后才为 true */
    val approved: Boolean = false,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
    val myId: String = "",
    val myName: String = "",
    val members: List<PartyMember> = emptyList(),
    /** 房主视角：等待批准的申请者 */
    val pending: List<PartyMember> = emptyList(),
    /** 邀请码有效期截止时间（毫秒），0 表示不是房主或已过期 */
    val inviteExpiresAt: Long = 0L,
    /** 最近一次发送失败的原因（中继拒收 / 网络异常），成功后自动清空 */
    val publishError: String? = null,
    val remoteSync: PartySyncState? = null,
)
