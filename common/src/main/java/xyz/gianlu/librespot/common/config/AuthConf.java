package xyz.gianlu.librespot.common.config;

import xyz.gianlu.librespot.common.enums.Strategy;

import java.util.Optional;

public class AuthConf {

    private String username;
    private String password;
    private String blob;
    private Strategy strategy = Strategy.ZEROCONF;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getBlob() {
        return blob;
    }

    public void setBlob(String blob) {
        this.blob = blob;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategyString) {
        this.strategy = Optional
                .of(Strategy.valueOf(strategyString))
                .orElse(Strategy.ZEROCONF);
    }
}
