package xyz.gianlu.librespot.player;

public class RpiMixerBypass {
    private static LinesHolder.LineWrapper backupLine;

    RpiMixerBypass() {
        backupLine = null;
    }

    public LinesHolder.LineWrapper getBackupLine() {
        return backupLine;
    }

    public void setBackupLine(LinesHolder.LineWrapper inputLine) { backupLine = inputLine; }
}