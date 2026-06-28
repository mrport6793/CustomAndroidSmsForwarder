# The shipped build is debug (no minification), but keep JavaMail intact in
# case a release build is ever produced — it relies on reflection + the
# META-INF provider descriptors.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn javax.**
-dontwarn com.sun.**
