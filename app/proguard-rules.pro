# LibVLC 3.7.7 has no AAR consumer rules. JNI exports bind native methods by
# class/name/signature; JNI_OnLoad also looks up callbacks and their result types.
# Keep only these entry points, rather than retaining all app/player/library code.
-keepclasseswithmembers,includedescriptorclasses class org.videolan.libvlc.** {
    native <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class org.videolan.libvlc.** {
    *** *FromNative(...);
}

# Both VLCObject and Equalizer expose this private native-handle field to JNI.
-keepclassmembers class org.videolan.libvlc.VLCObject { long mInstance; }
-keepclassmembers class org.videolan.libvlc.MediaPlayer$Equalizer { long mInstance; }

# libvlc.so calls surface/layout/SurfaceTexture methods by name (not only JNI
# exports). Retain the small surface bridge, including its private callbacks.
-keep,includedescriptorclasses class org.videolan.libvlc.AWindow { *; }

# JNI_OnLoad caches these constructors, even when discovery/equalization is unused.
-keep class org.videolan.libvlc.MediaPlayer$Title { <init>(...); }
-keep class org.videolan.libvlc.MediaPlayer$Chapter { <init>(...); }
-keep class org.videolan.libvlc.MediaPlayer$TrackDescription { <init>(...); }
-keep class org.videolan.libvlc.MediaDiscoverer$Description { <init>(...); }
-keep class org.videolan.libvlc.RendererDiscoverer$Description { <init>(...); }

# Hilt, Room (including generated *_Impl lookup), and WorkManager reflection are
# covered by their consumer/generated rules. Vault uses JCA directly, no reflection.
# Do not add package-wide dev.lumenara keeps or suppress missing-class warnings.
