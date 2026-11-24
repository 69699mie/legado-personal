package io.legado.app.ui.main.video

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.watchparty.PartyMember
import io.legado.app.help.watchparty.WatchPartyConfig
import io.legado.app.help.watchparty.WatchPartySession
import io.legado.app.help.watchparty.WatchPartyState
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet

private val partyBg = Color(0xCC111111)
private val partyHint = Color(0xFF8A8A8E)
private val partyLine = Color(0xFFE6E7EB)

/**
 * 播放页左侧的一起看入口：和「旋转屏幕」等按钮完全同一套圆形样式，
 * 未建房是普通圆形图标；建房后右上角挂人数/待审批角标（绿=已连接，红=有人敲门）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WatchPartyHud(
    modifier: Modifier = Modifier,
    state: WatchPartyState,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier,
        color = if (state.active) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f),
        shape = CircleShape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
    ) {
        Box {
            // 点开房间卡片；长按直接复制邀请码（房主想再发一个人时不用中断播放）
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(
                        onClick = onOpenSettings,
                        onLongClick = {
                            if (state.active) {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("一起看邀请码", WatchPartySession.inviteOf(state.code, state.password)))
                                android.widget.Toast.makeText(context, "已复制邀请码", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                onOpenSettings()
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.People, contentDescription = "一起看（长按复制邀请码）", tint = Color.White)
            }
            // 角标：优先显示待审批人数（红），否则显示房间在线人数（绿）
            val pendingCount = state.pending.size
            // members 里已经包含自己（会话层会把自己插进去），这里不能 +1
            val onlineCount = maxOf(1, state.members.size)
            val badgeText = when {
                state.isHost && pendingCount > 0 -> "$pendingCount"
                state.active -> "$onlineCount"
                else -> null
            }
            if (badgeText != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 3.dp)
                        .background(
                            if (state.isHost && pendingCount > 0) Color(0xFFE53935)
                            else if (state.connected) Color(0xFF43A047) else Color(0xFFFFA726),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(badgeText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun line() = HorizontalDivider(color = partyLine, thickness = 1.dp)

/**
 * 一起看面板。
 * - 横屏：靠屏幕左边贴边的小卡片（宽度 400dp，高度最多 380dp 内部滚动）。
 *   横屏时输入法键盘占屏幕右侧，面板放左边就不会被键盘顶下去，能边打字边看输入框。
 * - 竖屏：底部抽屉（AppModalBottomSheet），高度最多 80% 屏高，内容在抽屉里滚动。
 * 尺寸只按内容决定，不再用 fillMaxHeight 撑满整屏。
 */
@Composable
fun WatchPartyDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    if (!show) return
    var name by remember { mutableStateOf(WatchPartyConfig.displayName) }
    var inviteInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val state by WatchPartySession.state.collectAsState()
    val context = LocalContext.current

    fun copyInvite(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("一起看邀请码", text))
    }

    // 邀请码倒计时：用独立 ticker，别把「重启 while 循环」挂在 inviteExpiresAt 上
    //（之前重新开启邀请后倒计时不刷新，一直停在「已过期」）
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            kotlinx.coroutines.delay(500)
        }
    }
    // 退出房间后把残留提示清掉，否则未进房页面会被顶下去
    LaunchedEffect(state.active) {
        if (!state.active) message = null
    }
    // 被房主同意后清掉「已发送加入请求」这类提示，别再压在页面上
    LaunchedEffect(state.approved) {
        if (state.approved) message = null
    }
    val remainMs = (state.inviteExpiresAt - nowTick).coerceAtLeast(0L)
    val remainText = String.format("%02d:%02d", remainMs / 1000 / 60, remainMs / 1000 % 60)

    val body: @Composable ColumnScope.(Boolean) -> Unit = { compact ->
        PartyPanelContent(
            compact = compact,
            state = state,
            name = name,
            onNameChange = { name = it },
            inviteInput = inviteInput,
            onInviteChange = { inviteInput = it.take(40) },
            message = message,
            onMessage = { message = it },
            remainMs = remainMs,
            remainText = remainText,
            onCopyInvite = { text ->
                copyInvite(text)
                message = "已复制邀请码 $text"
            },
            onDismiss = onDismiss,
        )
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Surface(
                    color = AppBackgroundColor,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .padding(start = 24.dp, top = 8.dp, bottom = 8.dp)
                        .width(400.dp)
                        .heightIn(max = 380.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        body(true)
                    }
                }
            }
        }
    } else {
        AppModalBottomSheet(
            show = true,
            onDismissRequest = onDismiss,
            animateContentSize = false,
            contentPaddingEnabled = true,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                body(false)
            }
        }
    }
}

/** 面板内容本体：横竖屏共用，compact=true 时缩小间距以适配横屏的低高度 */
@Composable
private fun PartyPanelContent(
    compact: Boolean,
    state: WatchPartyState,
    name: String,
    onNameChange: (String) -> Unit,
    inviteInput: String,
    onInviteChange: (String) -> Unit,
    message: String?,
    onMessage: (String?) -> Unit,
    remainMs: Long,
    remainText: String,
    onCopyInvite: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val gap = if (compact) 12.dp else 20.dp
    val bigGap = if (compact) 20.dp else 32.dp

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = if (compact) 12.dp else 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (state.active) "一起看 · 房间" else "一起看",
            color = Color.Black,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.Black)
        }
    }

    if (state.active) {
        val invite = WatchPartySession.inviteOf(state.code, state.password)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "邀请码 $invite",
                color = Color.Black,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onCopyInvite(invite) }) { Text("复制", color = Color.Black) }
        }
        Spacer(Modifier.height(6.dp))
        if (state.isHost) {
            if (remainMs > 0) {
                Text("邀请剩余 $remainText 后失效", color = partyHint, fontSize = 12.sp)
            } else {
                Text("邀请已过期，对方现在加入会被拒绝", color = Color(0xFFD32F2F), fontSize = 12.sp)
            }
        } else if (!state.approved) {
            Text("已申请，等待房主同意（每 10 秒自动重试）…", color = Color(0xFFD32F2F), fontSize = 12.sp)
        } else if (state.hostLeft) {
            Text("房主已离开播放页，等待房主继续…", color = partyHint, fontSize = 12.sp)
        } else if (state.remoteSync == null && state.show == null) {
            Text("已加入房间，等待房主开始播放…", color = partyHint, fontSize = 12.sp)
        }
        if (state.isHost) {
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            OutlinedButton(
                onClick = {
                    WatchPartySession.reopenInvite()
                    onMessage(null)
                },
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF3C3C43)),
                modifier = Modifier.fillMaxWidth().height(42.dp),
            ) { Text("重新开启邀请", color = Color.Black, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
        if (state.isHost && state.pending.isNotEmpty()) {
            Text("请求加入（对方进入房间后才消失）", color = Color.Black, fontSize = 13.sp)
            state.pending.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.userName, color = Color.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (p.userId in state.approving) {
                        // 点过同意之后给个明确反馈，别让人以为没点上而反复点
                        Text("已同意，等待对方进入…", color = partyHint, fontSize = 12.sp)
                    } else {
                        TextButton(onClick = { WatchPartySession.approve(p.userId) }) { Text("同意", color = Color.Black) }
                        TextButton(onClick = { WatchPartySession.reject(p.userId) }) { Text("拒绝", color = Color(0xFFD32F2F)) }
                    }
                }
                line()
            }
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        }
        // 加入端：被取消过跟随 / 想手动回到房主那一集时，这里有个明确的入口
        val hostShow = state.show
        if (!state.isHost && state.approved && hostShow != null && hostShow.fid.isNotBlank()) {
            OutlinedButton(
                onClick = {
                    WatchPartySession.requestFollow()
                    onDismiss()
                },
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF3C3C43)),
                modifier = Modifier.fillMaxWidth().height(42.dp),
            ) {
                Text(
                    text = "跟随房主：" + hostShow.title + hostShow.episode,
                    color = Color.Black,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        }
        Text("在线", color = Color.Black, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        val me = PartyMember(
            userId = state.myId,
            userName = state.myName,
            isHost = state.isHost,
        )
        val shown = if (state.members.any { it.userId == state.myId }) {
            state.members
        } else {
            listOf(me) + state.members
        }
        shown.forEach { m ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 6.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.userName, color = Color.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (m.isHost) Text("房主", color = partyHint, fontSize = 12.sp)
            }
            line()
        }
        state.error?.let {
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            Text("连接异常：$it", color = Color(0xFFD32F2F), fontSize = 12.sp)
        }
        state.publishError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color(0xFFD32F2F), fontSize = 12.sp)
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = partyHint, fontSize = 13.sp)
        }
        Spacer(Modifier.height(bigGap))
        OutlinedButton(
            onClick = { WatchPartySession.leave() },
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFF3C3C43)),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("退出房间", color = Color.Black, fontWeight = FontWeight.Bold) }
        TextButton(
            onClick = { WatchPartySession.leave() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("换个房间（重新输入邀请码）", color = partyHint, fontSize = 13.sp) }
    } else {
        Text("你的昵称", color = Color.Black, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text("昵称", color = partyHint) },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(gap))
        line()
        Spacer(Modifier.height(gap))
        Text("邀请码", color = Color.Black, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = inviteInput,
            onValueChange = onInviteChange,
            placeholder = { Text("例如 XN3GZ9-K7m3Qp2a", color = partyHint) },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        (message ?: state.error)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = if (state.error != null) Color(0xFFD32F2F) else partyHint, fontSize = 13.sp)
        }
        Spacer(Modifier.height(bigGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    WatchPartyConfig.displayName = name
                    WatchPartySession.createRoom()
                        .onSuccess { onMessage(null) }
                        .onFailure { onMessage(it.message) }
                },
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFF3C3C43)),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("创建房间", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            OutlinedButton(
                onClick = {
                    WatchPartyConfig.displayName = name
                    WatchPartySession.joinRoom(inviteInput)
                        // 进房后房间卡片里已经有一条「已申请，等待房主同意…」，
                        // 这里再写一条就重复了
                        .onSuccess { onMessage(null) }
                        .onFailure { onMessage(it.message) }
                },
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFF3C3C43)),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("加入房间", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
    }
}
