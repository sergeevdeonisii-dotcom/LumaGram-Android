package org.telegram.utils.camera.roundvideo;
final class RoundVideoDiagnostics {
    int errors;
    boolean isEnabled() { return false; }
    void log(String message) {}
    void error(String message, Exception error) { errors++; }
}
