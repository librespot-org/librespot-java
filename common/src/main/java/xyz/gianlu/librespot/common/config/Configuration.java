package xyz.gianlu.librespot.common.config;



import xyz.gianlu.librespot.common.enums.DeviceType;

import java.util.Optional;
import java.util.UUID;

public class Configuration {

    private String deviceId;
    private CacheConf cache;
    private PlayerConf player;
    private ZeroConf zeroconf;
    private AuthConf auth;

    private String deviceName = "NONAME-Spotify";
    private DeviceType deviceType = DeviceType.Unknown;

    public CacheConf getCache() {
        return cache;
    }

    public void setCache(CacheConf cache) {
        this.cache = cache;
    }

    public PlayerConf getPlayer() {
        return player;
    }

    public void setPlayer(PlayerConf player) {
        this.player = player;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceTypeString) {
        this.deviceType = Optional
                .of(DeviceType.valueOf(deviceTypeString))
                .orElse(DeviceType.Unknown);
    }

    public ZeroConf getZeroconf() {
        return zeroconf;
    }

    public void setZeroconf(ZeroConf zeroconf) {
        this.zeroconf = zeroconf;
    }

    public void setDeviceType(DeviceType deviceType) {
        this.deviceType = deviceType;
    }

    public AuthConf getAuth() {
        return auth;
    }

    public void setAuth(AuthConf auth) {
        this.auth = auth;
    }

    public String getDeviceId() {
        if(this.deviceId == null) this.deviceId = UUID.randomUUID().toString();
        return deviceId;
    }
}
