package com.tvhome.launcher;

/** Tiny damped-spring integrator (semi-implicit Euler). */
final class Spring {
    float x, v, target, k, c, eps;

    Spring(float x, float k, float c, float eps) {
        this.x = x;
        this.target = x;
        this.k = k;
        this.c = c;
        this.eps = eps;
    }

    /** @return true while still moving */
    boolean step(float dt) {
        float a = -k * (x - target) - c * v;
        v += a * dt;
        x += v * dt;
        if (Math.abs(v) < eps * 8f && Math.abs(x - target) < eps) {
            x = target;
            v = 0f;
            return false;
        }
        return true;
    }

    void snap(float t) {
        x = t;
        target = t;
        v = 0f;
    }
}
