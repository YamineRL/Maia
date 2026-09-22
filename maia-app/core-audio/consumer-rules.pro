# The JNI layer resolves these by name with GetFieldID and FindClass, which
# R8 cannot see. Renaming any of them turns a working release build into a
# NoSuchFieldError at the first decode, so keep the whole package.
-keep class com.k2fsa.sherpa.onnx.** { *; }
