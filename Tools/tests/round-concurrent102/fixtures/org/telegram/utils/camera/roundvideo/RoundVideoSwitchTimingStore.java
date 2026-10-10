package org.telegram.utils.camera.roundvideo;
final class RoundVideoSwitchTimingStore {
    static int getAverageMs(RoundVideoSession.CameraFacing from, RoundVideoSession.CameraFacing to) { return 400; }
    static int recordAndGetAverageMs(RoundVideoSession.CameraFacing from, RoundVideoSession.CameraFacing to, int time) { return time; }
}
