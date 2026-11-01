# A plugin's jar names its index class in META-INF/services/<PluginIndex> and META-INF/alexandrite/<id>.json.
-keepnames interface org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
-keepnames class * implements org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
