# Hermes Bridge intentionally avoids package-wide keep rules.
#
# Android manifest entry points are covered by the default Android rules.
# kotlinx.serialization ships consumer rules for generated serializers used by
# retained @Serializable classes, and the Shizuku provider AAR ships its own
# narrow BinderContainer consumer rule.
#
# Shizuku loads typed UserServices by class name in separate app_process instances.
# Keep only those reflective entry points and their required no-arg constructors;
# the AIDL surfaces and implementations remain shrinkable otherwise.
-keep class io.github.arttvad9r.hermesbridge.HermesBridgeUserService {
    public <init>();
}

-keep class io.github.arttvad9r.hermesbridge.HermesBridgeUiControlUserService {
    public <init>();
}
