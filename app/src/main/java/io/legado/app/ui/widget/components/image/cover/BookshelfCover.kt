package io.legado.app.ui.widget.components.image.cover

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.core.ui.morph.BookCoverMorphAnchors
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.progressIndicator.AppLinearProgressIndicator

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BookshelfCover(
    name: String?,
    author: String?,
    path: String?,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier.fillMaxWidth(),
    isUpdating: Boolean = false,
    badgeText: String? = null,
    showBadgeDot: Boolean = false,
    /** 封面左上角文字（如最新话更新时间） */
    topLeftText: String? = null,
    leftBottomText: String? = null,
    sourceOrigin: String? = null,
    bookUrl: String? = null,
    onLoadFinish: (() -> Unit)? = null,
    showLoadingPlaceholder: Boolean = true,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedCoverKey: String? = null,
    contentBlur: Dp = 0.dp,
    overlayContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    Box(modifier = modifier) {
        CoilBookCover(
            name = name,
            author = author,
            path = path,
            modifier = coverModifier,
            sourceOrigin = sourceOrigin,
            bookUrl = bookUrl,
            preferCache = true,
            onLoadFinish = onLoadFinish,
            showLoadingPlaceholder = showLoadingPlaceholder,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedCoverKey = sharedCoverKey,
            contentBlur = contentBlur,
            overlayContent = overlayContent,
            badgeText = badgeText,
            showBadgeDot = showBadgeDot,
            leftBottomText = leftBottomText,
        )

        val overlayModifier = Modifier.then(
            if (animatedVisibilityScope != null) {
                with(animatedVisibilityScope) {
                    Modifier.animateEnterExit(
                        enter = fadeIn(),
                        exit = fadeOut()
                    )
                }
            } else Modifier
        )

        // 💡 左上角：最新话更新时间。ignoreThemeCornerRadiusOverride = true，让角标不被主题圆角覆盖成椭圆。
        if (!topLeftText.isNullOrEmpty()) {
            TextCard(
                text = topLeftText,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .then(overlayModifier)
                    .graphicsLayer {
                        alpha =
                            if (BookCoverMorphAnchors.isOriginCoverHidden(sharedCoverKey)) 0f else 1f
                    },
                cornerRadius = 4.dp,
                horizontalPadding = 4.dp,
                verticalPadding = 2.dp,
                ignoreThemeCornerRadiusOverride = true,
            )
        }

        // 💡 右上角：未读数字角标。同样绕过主题圆角覆盖。
        if (!badgeText.isNullOrEmpty()) {
            TextCard(
                text = badgeText,
                icon = if (showBadgeDot) Icons.Default.Update else null,
                iconSize = 12.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .then(overlayModifier)
                    .graphicsLayer {
                        alpha =
                            if (BookCoverMorphAnchors.isOriginCoverHidden(sharedCoverKey)) 0f else 1f
                    },
                cornerRadius = 4.dp,
                horizontalPadding = 4.dp,
                verticalPadding = 2.dp,
                ignoreThemeCornerRadiusOverride = true,
            )
        }

        // 💡 左下角：来源/类型标签。同样绕过主题圆角覆盖。
        if (!leftBottomText.isNullOrEmpty()) {
            TextCard(
                text = leftBottomText,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(2.dp)
                    .then(overlayModifier)
                    .graphicsLayer {
                        alpha =
                            if (BookCoverMorphAnchors.isOriginCoverHidden(sharedCoverKey)) 0f else 1f
                    },
                cornerRadius = 4.dp,
                horizontalPadding = 4.dp,
                verticalPadding = 2.dp,
                ignoreThemeCornerRadiusOverride = true,
            )
        }

        if (isUpdating) {
            AppLinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .height(3.dp)
                    .then(overlayModifier)
                    .graphicsLayer {
                        alpha =
                            if (BookCoverMorphAnchors.isOriginCoverHidden(sharedCoverKey)) 0f else 1f
                    }
            )
        }
    }
}