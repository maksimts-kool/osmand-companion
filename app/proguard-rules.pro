# R8 rules for the release build, on top of proguard-android-optimize.txt and the libraries' own (Sentry, WorkManager,
# Lottie, AndroidX ship theirs).

# OsmAnd's AIDL API: its parcelables and interfaces are written here and read back in OsmAnd's process by their names
# and field order, so nothing of them may be renamed or dropped.
-keep class net.osmand.aidlapi.** { *; }
-keep interface net.osmand.aidlapi.** { *; }
-keep class net.osmand.aidl.** { *; }
-keep interface net.osmand.aidl.** { *; }
-dontwarn net.osmand.**

# Java serialization: the trip being taken (TripStore's trip.ser, which outlives an update), and the places, ways and
# itineraries passed between screens in intents and saved state. The stream names each class and field, and without a
# serialVersionUID its default is worked out from the class's members, so they all stay as they are, or a trip saved by
# the version before wouldn't read.
-keep class dev.maksim.companion.** implements java.io.Serializable { *; }
-keepclassmembers enum dev.maksim.companion.** { *; }

# Shrinking and optimizing, but no renaming: crash reports (Sentry) then name the classes and methods as they are,
# without a mapping file to upload with each release.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# An annotation okio's classes carry, only for compilers; not there at run time, nor needed.
-dontwarn javax.annotation.Nullable
