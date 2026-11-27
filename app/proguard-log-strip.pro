# 正式包才应用的规则：把 android.util.Log 各等级日志调用整段删掉（省体积、避免泄露内部信息）。
# 带 -PpartyTestHooks=true 的验证包不会应用本文件，日志能在 logcat 里看到。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}
