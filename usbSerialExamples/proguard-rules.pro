-keep class org.cts.** { *; }
# Needed because isHotspotActive() reflects into getTetheredIfaces()
# If R8 removes or renames that hidden method, reflection fails.
-keep class android.net.* { *; }
