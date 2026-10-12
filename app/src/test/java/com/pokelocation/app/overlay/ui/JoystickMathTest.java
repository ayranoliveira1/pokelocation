package com.pokelocation.app.overlay.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 02 §3.5: ângulo atan2(−dy,dx) (y de tela para baixo) e magnitude limitada a 0..1. */
public class JoystickMathTest {
    private static final float EPS = 1e-4f;

    @Test
    public void angleFollowsMathConvention() {
        assertEquals(90f, JoystickView.angleDeg(0, -10), EPS);    // cima = norte
        assertEquals(0f, JoystickView.angleDeg(10, 0), EPS);     // direita = leste
        assertEquals(180f, JoystickView.angleDeg(-10, 0), EPS);  // esquerda = oeste
        assertEquals(-90f, JoystickView.angleDeg(0, 10), EPS);   // baixo = sul
        assertEquals(45f, JoystickView.angleDeg(10, -10), EPS);
    }

    @Test
    public void magnitudeIsProportionalAndClamped() {
        assertEquals(0.5f, JoystickView.magnitude(15, 0, 30), EPS);
        assertEquals(1f, JoystickView.magnitude(30, 40, 30), EPS);
        assertEquals(0f, JoystickView.magnitude(0, 0, 30), EPS);
        assertEquals(0f, JoystickView.magnitude(5, 5, 0), EPS);
    }
}
