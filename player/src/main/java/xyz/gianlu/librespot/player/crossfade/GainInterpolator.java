package xyz.gianlu.librespot.player.crossfade;

interface GainInterpolator {
    float interpolate(float x);

    float last();
}
