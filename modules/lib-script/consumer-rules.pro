# TTS-Server 移植：Rhino 反射可见性依赖"类名/方法名不被混淆"，务必保留！
-keepclassmembers class * {
    @com.github.jing332.script.annotation.ScriptInterface <methods>;
}
-keep class com.github.jing332.script.runtime.** { *; }
-keep class com.github.jing332.script.simple.** { *; }
-keep class com.github.jing332.compat.** { *; }
# org.mozilla.javascript 的 keep 与阅读主工程现有规则合并时注意去重
-keep class org.mozilla.javascript.** { *; }
