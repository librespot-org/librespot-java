package xyz.gianlu.librespot.common.config;

public class CacheConf {

    private boolean enabled = true;
    private String cacheDir ="./cache/";
    private boolean doCleanUp = true;


    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    public boolean isDoCleanUp() {
        return doCleanUp;
    }

    public void setDoCleanUp(boolean doCleanUp) {
        this.doCleanUp = doCleanUp;
    }
}
