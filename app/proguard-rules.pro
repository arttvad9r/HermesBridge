# Hermes Bridge intentionally avoids package-wide keep rules.
#
# Android manifest entry points are covered by the default Android rules.
# kotlinx.serialization ships consumer rules for generated serializers used by
# retained @Serializable classes, and the Shizuku provider AAR ships its own
# narrow BinderContainer consumer rule.
#
# Shizuku API 13.1.5 keeps newProcess private/deprecated, so Hermes Bridge must
# access this one legacy entry point reflectively until the privileged backend
# is migrated to a typed UserService. Preserve only that exact member: keeping
# the whole Shizuku class/package would hide shrinker regressions and largely
# disable useful optimization.
-keepclassmembers class rikka.shizuku.Shizuku {
    private static *** newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
