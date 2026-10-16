# TypedDroid intentionally avoids package-wide keep rules.
#
# Android manifest entry points are covered by the default Android rules.
# kotlinx.serialization ships consumer rules for generated serializers used by
# retained @Serializable classes.
# narrow BinderContainer consumer rule.
#
# Launched directly by Android app_process from the installed APK during embedded-ADB bootstrap.
-keep class io.github.arttvad9r.typeddroid.EmbeddedPrivilegedMain {
    public static void main(java.lang.String[]);
}
