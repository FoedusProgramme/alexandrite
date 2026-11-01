# PluginSet creates the index classes that a jar names through their no-argument constructor.
-keepclassmembers class * implements org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex {
    public <init>();
}

# RuntimeServices resolves only the types that carry @HostApi at run time.
-keepattributes RuntimeVisibleAnnotations
-keep @interface org.unitedsoftware.alexandrite.sdk.runtime.HostApi
-keep,allowshrinking,allowobfuscation @org.unitedsoftware.alexandrite.sdk.runtime.HostApi class *
