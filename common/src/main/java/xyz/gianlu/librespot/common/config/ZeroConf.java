package xyz.gianlu.librespot.common.config;

public class ZeroConf {

    public final static int MAX_PORT = 65536;
    public final static int MIN_PORT = 1024;
    private Boolean listenAll = Boolean.TRUE;
    private int listenPort = -1;
    private String[] interfaces = new String[0];

    public Boolean getListenAll() {
        return listenAll;
    }

    public void setListenAll(Boolean listenAll) {
        this.listenAll = listenAll;
    }

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int val) {
        if ( val > MIN_PORT && val < MAX_PORT || val == -1)
            this.listenPort = val;
        else
            throw new IllegalArgumentException("Illegal port number: " + val);


    }

    public String[] getInterfaces() {
        return interfaces;
    }

    public void setInterfaces(String[] interfaces) {
        this.interfaces = interfaces;
    }
}