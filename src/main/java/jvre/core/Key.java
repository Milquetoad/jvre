package jvre.core;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The keyboard keys jvre's input surface speaks in -- an L2-clean name per key
 * instead of a raw GLFW code (the surface stays in the user's vocabulary, never a
 * GLFW constant). For KEY STATE (navigation, shortcuts, WASD): {@link
 * Input#keyDown}/{@link Input#keyPressed}/{@link Input#keyReleased}. For TYPED
 * TEXT (text fields), use {@link Input#typedChars} instead -- the char stream
 * already handles layout, shift, and key-repeat.
 *
 * A practical set, not exhaustive: letters, digits, the common named/navigation
 * keys, the modifiers, and F1-F12. More can be added as needed (GLFW exposes the
 * numpad, media keys, etc.).
 */
public enum Key {
    A(GLFW_KEY_A), B(GLFW_KEY_B), C(GLFW_KEY_C), D(GLFW_KEY_D), E(GLFW_KEY_E),
    F(GLFW_KEY_F), G(GLFW_KEY_G), H(GLFW_KEY_H), I(GLFW_KEY_I), J(GLFW_KEY_J),
    K(GLFW_KEY_K), L(GLFW_KEY_L), M(GLFW_KEY_M), N(GLFW_KEY_N), O(GLFW_KEY_O),
    P(GLFW_KEY_P), Q(GLFW_KEY_Q), R(GLFW_KEY_R), S(GLFW_KEY_S), T(GLFW_KEY_T),
    U(GLFW_KEY_U), V(GLFW_KEY_V), W(GLFW_KEY_W), X(GLFW_KEY_X), Y(GLFW_KEY_Y),
    Z(GLFW_KEY_Z),

    NUM_0(GLFW_KEY_0), NUM_1(GLFW_KEY_1), NUM_2(GLFW_KEY_2), NUM_3(GLFW_KEY_3),
    NUM_4(GLFW_KEY_4), NUM_5(GLFW_KEY_5), NUM_6(GLFW_KEY_6), NUM_7(GLFW_KEY_7),
    NUM_8(GLFW_KEY_8), NUM_9(GLFW_KEY_9),

    SPACE(GLFW_KEY_SPACE), ENTER(GLFW_KEY_ENTER), ESCAPE(GLFW_KEY_ESCAPE),
    BACKSPACE(GLFW_KEY_BACKSPACE), TAB(GLFW_KEY_TAB), DELETE(GLFW_KEY_DELETE),
    INSERT(GLFW_KEY_INSERT),

    LEFT(GLFW_KEY_LEFT), RIGHT(GLFW_KEY_RIGHT), UP(GLFW_KEY_UP), DOWN(GLFW_KEY_DOWN),
    HOME(GLFW_KEY_HOME), END(GLFW_KEY_END),
    PAGE_UP(GLFW_KEY_PAGE_UP), PAGE_DOWN(GLFW_KEY_PAGE_DOWN),

    LEFT_SHIFT(GLFW_KEY_LEFT_SHIFT), RIGHT_SHIFT(GLFW_KEY_RIGHT_SHIFT),
    LEFT_CONTROL(GLFW_KEY_LEFT_CONTROL), RIGHT_CONTROL(GLFW_KEY_RIGHT_CONTROL),
    LEFT_ALT(GLFW_KEY_LEFT_ALT), RIGHT_ALT(GLFW_KEY_RIGHT_ALT),

    F1(GLFW_KEY_F1), F2(GLFW_KEY_F2), F3(GLFW_KEY_F3), F4(GLFW_KEY_F4),
    F5(GLFW_KEY_F5), F6(GLFW_KEY_F6), F7(GLFW_KEY_F7), F8(GLFW_KEY_F8),
    F9(GLFW_KEY_F9), F10(GLFW_KEY_F10), F11(GLFW_KEY_F11), F12(GLFW_KEY_F12);

    /** The underlying GLFW key code -- package-private so it never leaks to users. */
    final int glfw;

    Key(int glfw) {
        this.glfw = glfw;
    }
}
