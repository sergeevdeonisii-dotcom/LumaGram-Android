package org.telegram.messenger;
/** Metadata-only model: not Android's media parser/converter. */
public class VideoEditedInfo {
    public int framerate, resultWidth, originalWidth, resultHeight, originalHeight, bitrate, originalBitrate;
    public long startTime = -1, endTime = -1;
    public boolean roundVideo = true;
    public String originalPath;
}
