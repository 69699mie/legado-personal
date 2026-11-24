package io.legado.app.ui.main.video

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.watchparty.PartyMember
import io.legado.app.help.watchparty.WatchPartyConfig
import io.legado.app.help.watchparty.WatchPartySession
import io.legado.app.help.watchparty.WatchPartyState

private val partyBg = Color(0xCC111111)
private val partyHint = Color(0xFF8A8A8E)
private val partyLine = Color(0xFFE6E7EB)

/** 播放页右上角的一起看状态条 */
@Composable
fun WatchPartyHud(
    modifier: Modifier = Modifier,
    state: WatchPartyState,
    onOpenSettings: () -> Unit,
) {
    if (!state.active) {
        Box(
            modifier = modifier
                .background(partyBg, RoundedCornerShape(18.dp))
                .clickable { onOpenSettings() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("一起看", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        return
    }
    Row(
        modifier = modifier
            .background(partyBg, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    if (state.connected) Color(0xFF66BB6A) else Color(0xFFFFA726),
                    RoundedCornerShape(4.dp),
                )
        )
        Text(
            text = "${state.code} · ${if (state.isHost) "房主" else "跟随"} · ${state.members.size}人",
            color = Color.White,
            fontSize = 12.sp,
        )
        Box(
            modifier = Modifier
                .background(Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                .clickable { WatchPartySession.leave() }
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("退出", color = Color(0xFFE6E6E6), fontSize = 12.sp)
        }
    }
}

@Composable
private fun line() = HorizontalDivider(color = partyLine, thickness = 1.dp)

/** 一起看面板：与「设置」页同一套浅色样式 */
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
    val remainMs = (state.inviteExpiresAt - nowTick).coerceAtLeast(0L)
    val remainText = String.format("%02d:%02d", remainMs / 1000 / 60, remainMs / 1000 % 60)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = AppBackgroundColor, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
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
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            copyInvite("阅影一起看 邀请码 $invite")
                            message = "已复制邀请码"
                        }) { Text("复制", color = Color.Black) }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (state.isHost) {
                        if (remainMs > 0) {
                            Text("邀请剩余 $remainText 后失效", color = partyHint, fontSize = 12.sp)
                        } else {
                            Text("邀请已过期，对方现在加入会被拒绝", color = Color(0xFFD32F2F), fontSize = 12.sp)
                        }
                    } else if (!state.approved) {
                        Text("等待房主确认加入…", color = Color(0xFFD32F2F), fontSize = 12.sp)
                    }
                    if (state.isHost) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                WatchPartySession.reopenInvite()
                                message = null
                            },
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Color(0xFF3C3C43)),
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                        ) { Text("重新开启邀请", color = Color.Black, fontSize = 13.sp) }
                    }
                    Spacer(Modifier.height(24.dp))
                    if (state.isHost && state.pending.isNotEmpty()) {
                        Text("请求加入", color = Color.Black, fontSize = 13.sp)
                        state.pending.forEach { p ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(p.userName, color = Color.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { WatchPartySession.approve(p.userId) }) { Text("同意", color = Color.Black) }
                                TextButton(onClick = { WatchPartySession.reject(p.userId) }) { Text("拒绝", color = Color(0xFFD32F2F)) }
                            }
                            line()
                        }
                        Spacer(Modifier.height(16.dp))
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
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(m.userName, color = Color.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (m.isHost) Text("房主", color = partyHint, fontSize = 12.sp)
                        }
                        line()
                    }
                    state.error?.let {
                        Spacer(Modifier.height(12.dp))
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
                    Spacer(Modifier.height(32.dp))
                    OutlinedButton(
                        onClick = { WatchPartySession.leave() },
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color(0xFF3C3C43)),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("退出房间", color = Color.Black, fontWeight = FontWeight.Bold) }
                } else {
                    Text("你的昵称", color = Color.Black, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("昵称", color = partyHint) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(20.dp))
                    line()
                    Spacer(Modifier.height(20.dp))
                    Text("邀请码", color = Color.Black, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inviteInput,
                        onValueChange = { inviteInput = it.take(40) },
                        placeholder = { Text("例如 XN3GZ9-K7m3Qp2a", color = partyHint) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    (message ?: state.error)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = if (state.error != null) Color(0xFFD32F2F) else partyHint, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(32.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                WatchPartyConfig.displayName = name
                                WatchPartySession.createRoom()
                                    .onSuccess { message = null }
                                    .onFailure { message = it.message }
                            },
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, Color(0xFF3C3C43)),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("创建房间", color = Color.Black, fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = {
                                WatchPartyConfig.displayName = name
                                WatchPartySession.joinRoom(inviteInput)
                                    .onSuccess { message = "已发送加入请求，等房主同意" }
                                    .onFailure { message = it.message }
                            },
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, Color(0xFF3C3C43)),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("加入房间", color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
