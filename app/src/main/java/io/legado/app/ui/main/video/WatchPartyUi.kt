package io.legado.app.ui.main.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.watchparty.PartyChatMessage
import io.legado.app.help.watchparty.WatchPartyConfig
import io.legado.app.help.watchparty.WatchPartySession
import io.legado.app.help.watchparty.WatchPartyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val partyBg = Color(0xCC111111)
private val partyAccent = Color(0xFF4FC3F7)

/** 播放页右上角的一起看状态条 */
@Composable
fun WatchPartyHud(
    modifier: Modifier = Modifier,
    state: WatchPartyState,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
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
        if (state.isHost && state.pending.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .background(Color(0xFFFFB74D), RoundedCornerShape(10.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("${state.pending.size} 人请求加入", color = Color.Black, fontSize = 11.sp)
            }
        }
        Box(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .clickable { onOpenChat() }
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("聊天", color = partyAccent, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .clickable { WatchPartySession.leave() }
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("退出", color = Color(0xFFFF8A80), fontSize = 12.sp)
        }
    }
}

/** 创建 / 加入房间，兼做 Supabase 配置 */
@Composable
fun WatchPartyDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onOpenChat: () -> Unit = {},
) {
    if (!show) return
    var name by remember { mutableStateOf(WatchPartyConfig.displayName) }
    var joinCode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val state by WatchPartySession.state.collectAsState()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Color.White,
        focusedBorderColor = Color.White.copy(alpha = 0.8f),
        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
        focusedLabelColor = Color.White,
        unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
            )
            Surface(
                color = Color(0xFF141414),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(300.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (state.active) "一起看房间" else "一起看",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            TextButton(onClick = onDismiss) { Text("关闭", color = Color.White.copy(alpha = 0.7f)) }
                        }
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                if (state.active) {
                    Text("房间码：${state.code}   密码：${state.password}", fontWeight = FontWeight.Bold)
                    if (!state.isHost && !state.approved) {
                        Text("等待房主同意…", color = partyAccent, fontSize = 12.sp)
                    }
                    Text(
                        text = if (state.isHost) {
                            "你是房主，你的播放/暂停/进度会同步给房间里其他人。把房间码告诉他们即可加入。"
                        } else {
                            "你在跟随房主播放。房主暂停、拖动进度时这里会自动跟上。"
                        },
                        fontSize = 12.sp,
                    )
                    Text(
                        text = "在线：" + state.members.joinToString("、") {
                            it.userName + (if (it.isHost) "（房主）" else "")
                        },
                        fontSize = 12.sp,
                    )
                    if (state.isHost && state.pending.isNotEmpty()) {
                        Text("待批准：", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        state.pending.forEach { p ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(p.userName, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { WatchPartySession.approve(p.userId) }) { Text("同意") }
                                TextButton(onClick = { WatchPartySession.reject(p.userId) }) { Text("拒绝", color = Color(0xFFD32F2F)) }
                            }
                        }
                    }
                    state.error?.let {
                        Text("连接异常：$it", color = Color(0xFFD32F2F), fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        WatchPartySession.leave()
                        message = "已退出房间"
                    }) { Text("退出房间") }
                    if (state.approved) {
                        TextButton(onClick = onOpenChat) { Text("聊天", color = partyAccent) }
                    }
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("你的昵称") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(8) },
                        label = { Text("房间密码（留空自动生成）") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it.filter { c -> c.isLetterOrDigit() }.uppercase().take(6) },
                        label = { Text("6 位房间码") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                message?.let { Text(it, fontSize = 12.sp, color = partyAccent) }
            }
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.active) {
                                TextButton(onClick = onDismiss) { Text("关闭") }
                            } else {
                                TextButton(onClick = {
                                    WatchPartyConfig.displayName = name
                                    val pwd = password.ifBlank { WatchPartySession.newPassword() }
                                    password = pwd
                                    WatchPartySession.createRoom(pwd)
                                        .onSuccess { message = "房间已创建：$it　密码：$pwd" }
                                        .onFailure { message = it.message }
                                }) { Text("创建房间") }
                                TextButton(onClick = {
                                    WatchPartyConfig.displayName = name
                                    WatchPartySession.joinRoom(joinCode, password)
                                        .onSuccess { message = "已加入房间：$it，等待房主同意" }
                                        .onFailure { message = it.message }
                                }) { Text("加入房间") }
                                Spacer(modifier = Modifier.weight(1f))
                                TextButton(onClick = onDismiss) { Text("关闭") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 播放页聊天浮层 */
@Composable
fun WatchPartyChatPanel(
    show: Boolean,
    state: WatchPartyState,
    onDismiss: () -> Unit,
) {
    if (!show) return
    var input by remember { mutableStateOf("") }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xF21A1A1A), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable { }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "房间 ${state.code} · ${state.members.size} 人",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "关闭",
                    color = partyAccent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onDismiss() },
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.messages.asReversed()) { msg ->
                    ChatRow(msg, timeFmt.format(Date(msg.sentAt)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(80) },
                    placeholder = { Text("说点什么…", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardActions = KeyboardActions(onDone = {
                        WatchPartySession.sendChat(input)
                        input = ""
                    }),
                )
                TextButton(onClick = {
                    WatchPartySession.sendChat(input)
                    input = ""
                }) { Text("发送", color = partyAccent) }
            }
        }
    }
}

@Composable
private fun ChatRow(msg: PartyChatMessage, time: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "${msg.userName} $time",
            color = if (msg.mine) partyAccent else Color(0xFF9E9E9E),
            fontSize = 11.sp,
        )
        Text(msg.text, color = Color.White, fontSize = 13.sp)
    }
}

/** 一起看服务器配置（入口在 App 设置 → 其他设置） */
@Composable
fun WatchPartyServerDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    if (!show) return
    var url by remember { mutableStateOf(WatchPartyConfig.url) }
    var key by remember { mutableStateOf(WatchPartyConfig.apiKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("一起看服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "已内置默认服务器，正常不用改。只有换成自建 Supabase 项目时才需要填写。",
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("项目地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("publishable / anon key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                WatchPartyConfig.url = url
                WatchPartyConfig.apiKey = key
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
