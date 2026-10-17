package com.pokelocation.app.ui.setup;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntPredicate;

/** Máquina de estados do Setup Wizard. Java puro: a verificação de cada passo é injetada. */
final class Wizard {
    static final int WELCOME = 0, STEP = 1, OVERLAY_DENIAL = 2, LOCATION_DENIED = 3, COMPLETE = 4;
    /** Passos em ordem fixa. */
    static final int NOTIFICATION = 0, LOCATION = 1, OVERLAY = 2, DEV_OPTIONS = 3, MOCK = 4, COUNT = 5;
    /** Famílias de instrução do passo DEV_OPTIONS. */
    static final int FAMILY_SAMSUNG = 0, FAMILY_ONEPLUS = 1, FAMILY_XIAOMI = 2, FAMILY_DEFAULT = 3;

    private final IntPredicate ok;
    final boolean hub;          // aberto com setup_wizard_completed=true (menu "Setup & Help")
    int[] steps;                // passos do fluxo: pendentes no 1º uso, os 5 no hub
    int screen, index, skipped, attempted, overlayDenials; // skipped/attempted = bitmask por passo
    int fixing = -1;            // passo aberto pelo "Fix" do Complete; -1 = fluxo normal
    boolean verificationError;

    Wizard(IntPredicate ok, boolean alreadyCompleted) {
        this.ok = ok;
        hub = alreadyCompleted;
        int[] tmp = new int[COUNT];
        int n = 0;
        for (int s = 0; s < COUNT; s++) if (hub || !ok.test(s)) tmp[n++] = s;
        steps = Arrays.copyOf(tmp, n);
        screen = hub || n == 0 ? COMPLETE : WELCOME;
    }

    int current() { return fixing >= 0 ? fixing : steps[index]; }

    int stepNumber() { return fixing >= 0 ? fixing + 1 : index + 1; }

    int stepTotal() { return fixing >= 0 ? COUNT : steps.length; }

    boolean showIveDone() {
        int s = current();
        return (s == DEV_OPTIONS || s == MOCK) && (attempted & bit(s)) != 0;
    }

    boolean isSkipped(int s) { return (skipped & bit(s)) != 0; }

    /** "Let's Go". */
    void start() { goFrom(0); }

    /** Botão primário: a Activity dispara a ação do passo logo depois. */
    void primary() {
        verificationError = false;
        attempted |= bit(current());
    }

    /** onResume: avança se o passo atual ficou satisfeito (pulando os seguintes já satisfeitos). */
    void revalidate() {
        if (screen == WELCOME || screen == COMPLETE) return;
        if (ok.test(current())) advance();
    }

    void advance() {
        if (fixing >= 0) toComplete();
        else goFrom(index + 1);
    }

    void skip() {
        skipped |= bit(current());
        advance();
    }

    void iveDone() {
        if (ok.test(current())) advance();
        else verificationError = true;
    }

    /** Resultado do pedido de permissão (NOTIFICATION ou LOCATION) do passo atual. */
    void permissionResult(boolean granted) {
        if (granted) advance();
        else if (current() == LOCATION) screen = LOCATION_DENIED;
        else if (fixing >= 0) verificationError = true;
        else advance();             // notificação negada não bloqueia o fluxo normal
    }

    /** Voltou da tela de overlay do sistema. Bloqueio a partir da 2ª negação ou com restricted settings. */
    void overlayReturned(boolean restrictedLikely) {
        if (ok.test(OVERLAY)) {
            advance();
            return;
        }
        overlayDenials++;
        screen = overlayDenials >= 2 || restrictedLikely ? OVERLAY_DENIAL : STEP;
    }

    void fix(int s) {
        fixing = s;
        verificationError = false;
        screen = STEP;
    }

    /** Fim de um Fix, "Back to Setup" ou fim do fluxo. */
    void toComplete() {
        fixing = -1;
        verificationError = false;
        screen = COMPLETE;
    }

    /** Voltar de LocationDenied/OverlayDenial para o passo. */
    void toStep() { screen = STEP; }

    private void goFrom(int i) {
        verificationError = false;
        while (i < steps.length && ok.test(steps[i])) i++;
        if (i >= steps.length) {
            screen = COMPLETE;
        } else {
            index = i;
            screen = STEP;
        }
    }

    private static int bit(int s) { return 1 << s; }

    int[] save() {
        return new int[]{screen, index, skipped, attempted, overlayDenials, fixing, verificationError ? 1 : 0};
    }

    void restore(int[] a, int[] savedSteps) {
        if (a == null || a.length < 7 || savedSteps == null) return;
        steps = savedSteps;
        screen = a[0];
        index = a[1];
        skipped = a[2];
        attempted = a[3];
        overlayDenials = a[4];
        fixing = a[5];
        verificationError = a[6] != 0;
    }

    static int family(String manufacturer) {
        String m = manufacturer == null ? "" : manufacturer.toLowerCase(Locale.ROOT);
        if (m.contains("samsung") || m.contains("vivo")) return FAMILY_SAMSUNG;
        if (m.contains("oneplus") || m.contains("oppo") || m.contains("realme")) return FAMILY_ONEPLUS;
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return FAMILY_XIAOMI;
        return FAMILY_DEFAULT;
    }

    private static final String[] BRANDS = {"samsung", "Samsung", "xiaomi", "Xiaomi", "oneplus", "OnePlus",
            "oppo", "OPPO", "vivo", "Vivo", "huawei", "Huawei", "honor", "Honor", "google", "Google"};

    /** Nome da marca para "Follow these steps on your %s device:"; null = texto genérico. */
    static String brand(String manufacturer) {
        String m = manufacturer == null ? "" : manufacturer.toLowerCase(Locale.ROOT);
        for (int i = 0; i < BRANDS.length; i += 2) if (m.contains(BRANDS[i])) return BRANDS[i + 1];
        return null;
    }
}
