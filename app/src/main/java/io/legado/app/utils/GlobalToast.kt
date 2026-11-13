package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import splitties.init.appCtx

object GlobalToast {

    private val handler = Handler(Looper.getMainLooper())
    private var currentView: ComposeView? = null
    private var currentOwner: ToastLifecycleOwner? = null
    private var dismissRunnable: Runnable? = null

    @SuppressLint("StaticFieldLeak")
    private val windowManager by lazy {
        appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun show(message: String, durationMs: Long = 2000L) {
        if (message.isBlank()) return
        handler.post {
            if (!Settings.canDrawOverlays(appCtx)) {
                // 未授予悬浮窗权限，降级为系统原生 Toast，保证文字至少可见
                Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show()
                return@post
            }
            dismissImmediately()
            val owner = ToastLifecycleOwner()
            val composeView = ComposeView(appCtx).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                owner.onCreate()
            }
            var visible by mutableStateOf(false)
            composeView.setContent {
                LaunchedEffect(Unit) { visible = true }
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xF2FFFFFF), RoundedCornerShape(24.dp))
                            .padding(horizontal = 28.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = message,
                            color = Color(0xFF333333),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 200
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blurBehindRadius = 25
                }
            }

            try {
                windowManager.addView(composeView, params)
                currentView = composeView
                currentOwner = owner
                owner.onResume()
                val runnable = Runnable {
                    visible = false
                    handler.postDelayed({ dismissImmediately() }, 300L)
                }
                dismissRunnable = runnable
                handler.postDelayed(runnable, durationMs)
            } catch (e: Exception) {
                e.printStackTrace()
                dismissImmediately()
                Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dismissImmediately() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        currentView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        currentView = null
        currentOwner?.onDestroy()
        currentOwner = null
    }

    private class ToastLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore = ViewModelStore()
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun onResume() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            viewModelStore.clear()
        }
    }
}