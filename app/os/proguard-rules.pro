# sherpa-onnx: JNI looks up Kotlin classes and fields by name
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# litertlm (on-device AI) uses JNI and reflection
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn **
