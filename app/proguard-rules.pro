# CloudSaver R8 rules. AndroidX libraries ship their own consumer rules;
# only project-specific extras live here.

# Keep enum names: they are persisted as strings in Room and in the JSON snapshot.
-keepclassmembers enum app.cloudsaver.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Snapshot codec reads/writes org.json (framework classes, always present).
-dontwarn org.json.**

# Media3 uses reflection for some codec paths.
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
