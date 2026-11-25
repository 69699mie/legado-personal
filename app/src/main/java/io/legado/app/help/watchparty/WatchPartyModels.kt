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

/**
 * 房主的「当前节目」广播：换集 / 换片时比播放同步更早发出，
 * 加入端据此提前跳转（不用等房主真正开播才收到 sync）。
 */
data class PartyShowState(
    val fid: String = "",
    val episode: String = "",
    val title: String = "",
    val sentAt: Long = 0L,
)

/**
 * 加入端向房主索要「房主正在播的那一集」的短时直链。
 * 只在本地按 fid / 剧名+集号都对不上时才发，不是常态广播。
 * 防泄露：按 fid 60 秒冷却、每个房间最多 3 次、房主只发给已同意的成员。
 */
data class PartyLinkRequest(
    val userId: String = "",
    val fid: String = "",
)

/**
 * 房主点对点返回的短时直链（只发给申请的 userId）。
 * 防泄露：接收方只认 to == 自己、只认自己刚请求过的 fid、且必须在 expiresAt 之前（默认 120 秒）。
 */
data class PartyLink(
    val toId: String = "",
    val fid: String = "",
    val url: String = "",
    val expiresAt: Long = 0L,
    /** 房主侧这一集的文件大小（播放前要用它做流式读取，缺失会报"无法获取视频文件大小"） */
    val size: Long = 0L,
)

/** 房间成员 */data class PartyMember(
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
    /** 房主最近一次广播的「当前节目」（加入端跟随用） */
    val show: PartyShowState? = null,
    /** 房主已离开播放页：加入端暂停播放并等待房主继续 */
    val hostLeft: Boolean = false,
    /** 房主端：刚点过「同意」、还在等对方真正进房的申请人（按钮要变成等待态，避免连点） */
    val approving: Set<String> = emptySet(),
    /** 加入端手动点「跟随房主」时放进来的一次请求，主页面消费后清空 */
    val followRequest: PartyShowState? = null,
    /** 房主端：收到某个加入端索要当前集直链（播放页消费后清空） */
    val linkRequest: PartyLinkRequest? = null,
    /** 加入端：收到房主返回的短时直链（主页面消费后清空） */
    val incomingLink: PartyLink? = null,
)
