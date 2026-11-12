# Project-specific R8 rules belong here. The current dependencies publish their
# consumer rules, and Kotlin serialization generates adapters without reflection.

# Missing RTSP setup checks use these pinned parser frames as allowlisted evidence.
# Keep their names and bodies so optimization cannot erase or rename that evidence.
-keep class androidx.media3.exoplayer.rtsp.RtspMediaTrack {
    *** generatePayloadFormat(...);
    *** processH265FmtpAttribute(...);
}
