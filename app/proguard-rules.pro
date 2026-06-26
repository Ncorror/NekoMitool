# Стандартные правила Android
-keep class androidx.** { *; }
-keep class com.google.android.material.** { *; }

# Сохраняем USB-классы (используются через reflection в некоторых версиях SDK)
-keep class android.hardware.usb.** { *; }

# Наши протоколы — не обфусцировать имена методов (для отладки логов)
-keepnames class ru.forum.adbfastboottool.** { *; }
