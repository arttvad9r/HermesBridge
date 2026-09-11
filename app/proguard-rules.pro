# Hermes Bridge intentionally avoids package-wide keep rules.
#
# Android manifest entry points are covered by the default Android rules.
# kotlinx.serialization ships consumer rules for generated serializers used by
# retained @Serializable classes, and the Shizuku provider AAR ships its own
# narrow BinderContainer consumer rule.
#
# Shizuku loads the typed privileged UserService by class name in a separate
# app_process. Keep only that reflective entry point and its required no-arg
# constructor; the AIDL surface and implementation remain shrinkable otherwise.
-keep class io.github.arttvad9r.hermesbridge.HermesBridgeUserService {
    public <init>();
}