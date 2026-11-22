# JNI создаёт LayerInfo через конструктор -> R8 не должен его удалять/менять
-keep class com.vibetoon.nativebridge.LayerInfo { <init>(...); }
