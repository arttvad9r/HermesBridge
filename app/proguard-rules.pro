# Hermes Bridge intentionally starts with no package-wide keep rules.
#
# Android manifest entry points are covered by the default Android rules.
# kotlinx.serialization ships consumer rules for generated serializers used by
# retained @Serializable classes, and the Shizuku provider AAR ships its own
# narrow BinderContainer consumer rule.
#
# Add a project keep rule only after a minified release build/runtime test proves
# that a specific reflective entry point requires one. Do not use broad
# `-keep class io.github.arttvad9r.hermesbridge.** { *; }` rules because they
# would hide shrinker regressions and largely disable R8 optimization.
