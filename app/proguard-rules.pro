# What R8 may not take away: everything here is reached by name at run time,
# not by a call R8 can see. Each rule says which.

# JNI. The native side finds its functions by symbol - Java_nl_mattix_andamp_..._method -
# and never calls FindClass, so the class and method names on the Kotlin side are
# the linkage. Renamed, the library loads and every call throws UnsatisfiedLinkError.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# LuaJ. A plug-in is a script; the interpreter reaches its own library classes
# reflectively while it builds the sandbox, so shrinking by call graph removes
# parts a .lua file needs.
-keep class org.luaj.** { *; }
-dontwarn org.luaj.**

# LuaJ compiles against the JDK's script API, which Android does not have. No
# plug-in path here uses it.
-dontwarn javax.script.**

# The modern skin engine. Releases carry it whole, including the parts no screen
# reaches, so a minified build can run all of it. It reads its default element
# set as Java resources by absolute path and uses no reflection; the rule is
# about reach, not names.
-keep class nl.mattix.andamp.modernskin.** { *; }

# Media3 ships its own consumer rules.
