package xyz.gianlu.librespot.common.enums;

public enum DeviceType {
    Unknown(0, "unknown"),
    Computer(1, "computer"),
    Tablet(2, "tablet"),
    Smartphone(3, "smartphone"),
    Speaker(4, "speaker"),
    TV(5, "tv"),
    AVR(6, "avr"),
    STB(7, "stb"),
    AudioDongle(8, "audiodongle");

    public final int val;
    public final String name;

    DeviceType(int val, String name) {
        this.val = val;
        this.name = name;
    }


}

