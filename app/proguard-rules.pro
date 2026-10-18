# The Onyx SDK talks to the Boox system services via reflection and hidden APIs.
-keep class com.onyx.** { *; }
-dontwarn com.onyx.**

# Optional integrations of the SDK's transitive dependencies (OkHttp TLS providers, Joda, SLF4J), never loaded.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.joda.convert.**
-dontwarn org.slf4j.impl.**
