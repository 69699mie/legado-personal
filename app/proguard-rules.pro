# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# 混合时不使用大小写混合，混合后的类名为小写
-dontusemixedcaseclassnames

# 这句话能够使我们的项目混淆后产生映射文件
# 包含有类名->混淆后类名的映射关系
-verbose

# 保留Annotation不混淆
-keepattributes *Annotation*,InnerClasses

# 避免混淆泛型
-keepattributes Signature

# 指定混淆是采用的算法，后面的参数是一个过滤器
# 这个过滤器是谷歌推荐的算法，一般不做更改
-optimizations !code/simplification/cast,!field/*,!class/merging/*

-flattenpackagehierarchy

#############################################
#
# Android开发中一些需要保留的公共部分
#
#############################################
# 屏蔽错误Unresolved class name
#noinspection ShrinkerUnresolvedReference

# 移除Log类打印各个等级日志的代码，打正式包的时候可以做为禁log使用，这里可以作为禁止log打印的功能使用
# 记得proguard-android.txt中一定不要加-dontoptimize才起作用
# 另外的一种实现方案是通过BuildConfig.DEBUG的变量来控制
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# 保持js引擎调用的java类
-keep class * extends io.legado.app.help.JsExtensions{*;}
# 数据类
-keep class **.data.entities.**{*;}
# Gson反序列化用的数据传输类
-keep class io.legado.app.model.translation.**{*;}
-keep class io.legado.app.domain.model.DictPair{*;}
-keep class io.legado.app.domain.model.BookDictionary{*;}
-keep class io.legado.app.domain.model.TextChunk{*;}
-keep class io.legado.app.domain.model.ModuleDef{*;}
-keep class io.legado.app.domain.model.ModuleItem{*;}
-keep class io.legado.app.domain.model.CustomSetItem{*;}
-keep class io.legado.app.data.repository.GoogleTranslateResponse{*;}
-keep class io.legado.app.data.repository.GoogleSentence{*;}
-keep class io.legado.app.data.repository.GoogleSpell{*;}
-keep class io.legado.app.data.repository.OpenAIResponse{*;}
-keep class io.legado.app.data.repository.OpenAIChoice{*;}
-keep class io.legado.app.data.repository.OpenAIMessage{*;}
# 缓存 Cookie
-keep class **.help.http.CookieStore{*;}
-keep class **.help.CacheManager{*;}
# StrResponse
-keep class **.help.http.StrResponse{*;}

# markwon
-dontwarn org.commonmark.ext.gfm.**

# hutool-core hutool-crypto（书源 JS 通过 Packages.cn.hutool.* 反射调用，需整体保留）
-keep class cn.hutool.crypto.**{*;}
-keep class cn.hutool.core.codec.**{*;}
-keep class cn.hutool.core.util.**{*;}
-dontwarn cn.hutool.**

-keep class okhttp3.*{*;}
-keep class okio.*{*;}
-keep class com.jayway.jsonpath.*{*;}

# LiveEventBus
-keepclassmembers class androidx.lifecycle.LiveData {
    *** mObservers;
    *** mActiveCount;
}
-keepclassmembers class androidx.arch.core.internal.SafeIterableMap {
    *** size();
    *** putIfAbsent(...);
}

## ChangeBookSourceDialog initNavigationView
-keepclassmembers class androidx.appcompat.widget.Toolbar {
    *** mNavButtonView;
}

# FileDocExtensions.kt treeDocumentFileConstructor
-keep class androidx.documentfile.provider.TreeDocumentFile {
    <init>(...);
}

# JsoupXpath
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.AxisSelector{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.NodeTest{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.Function{*;}

## JSOUP
-keep class org.jsoup.**{*;}
-dontwarn org.jspecify.annotations.NullMarked

## ExoPlayer 反射设置ua 保证该私有变量不被混淆
-keepclassmembers class androidx.media3.datasource.cache.CacheDataSource$Factory {
    *** upstreamDataSourceFactory;
}

## 对外提供api
-keep class io.legado.app.api.ReturnData{*;}

# Cronet
-keepclassmembers class org.chromium.net.X509Util {
    *** sDefaultTrustManager;
    *** sTestTrustManager;
}

# Throwable
-keepnames class * extends java.lang.Throwable
-keepclassmembernames,allowobfuscation class * extends java.lang.Throwable{*;}

# ==========================================
# 忽略 Ktor 在 Android 上对 Java SE 管理类的引用
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.IntellijIdeaDebugDetector
-keep,allowobfuscation class io.ktor.util.debug.** { *; }

# 保留 Coil 3 通过 ServiceLoader 注册的所有解码器，防止 R8 full-mode 剥离
-keep class * extends coil3.util.DecoderServiceLoaderTarget { *; }
-keep class * extends coil3.util.FetcherServiceLoaderTarget { *; }

# ==========================================
# 核心新增：Media3 播放器防混淆（防止 R8 导致 HLS 崩溃和播放报错）
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 核心新增：Ktor Server 防混淆（保障本地代理正常运行）
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# 保留所有 Launcher 图标组件类（防止 R8 重命名导致切换图标崩溃）
-keep class io.legado.app.ui.main.LauncherW { *; }
-keep class io.legado.app.ui.main.Launcher0 { *; }
-keep class io.legado.app.ui.main.Launcher1 { *; }
-keep class io.legado.app.ui.main.Launcher2 { *; }
-keep class io.legado.app.ui.main.Launcher3 { *; }
-keep class io.legado.app.ui.main.Launcher4 { *; }
-keep class io.legado.app.ui.main.Launcher5 { *; }
-keep class io.legado.app.ui.main.Launcher6 { *; }
-keep class io.legado.app.ui.main.MainActivity { *; }

# ==========================================
# 🌟 终极杀招：全量保护 Room 数据库及其实体，防止 R8 混淆引发的 Koin 崩溃！
-keep class io.legado.app.data.entities.** { *; }
-keep class io.legado.app.data.dao.** { *; }
-keep class io.legado.app.data.AppDatabase { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
# 强制保留 Room 为 DAO 自动生成的实现类
-keep class * implements io.legado.app.data.dao.VideoScrapedDao { *; }

# 保留 Compose 动画类，防止类合并导致动画直接跳变
-keep,allowshrinking,allowobfuscation class androidx.compose.animation.** { *; }
-keep @androidx.compose.runtime.Composable class * { *; }
