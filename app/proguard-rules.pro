# Media3 and Compose ship their own consumer rules. Add app-specific keeps here.

# ONNX Runtime's native code creates and calls its Java classes (tensors, results, node info) by name.
-keep class ai.onnxruntime.** { *; }

# libvrwma.so (WMA decoding) binds to these natives by name.
-keepclasseswithmembers class app.viberadio.playback.wma.WmaNative { native <methods>; }
