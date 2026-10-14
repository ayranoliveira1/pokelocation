package com.pokelocation.app.social.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.pokelocation.app.ui.main.MainActivity;

import org.junit.Test;

/** Para onde o app vai depois do login (email/senha ou Google), conforme quem abriu o Login. */
public class LoginRouteTest {
    @Test public void defaultGoesBackToMain() {
        assertEquals(MainActivity.class, LoginActivity.next(null));
        assertEquals(MainActivity.class, LoginActivity.next(LoginActivity.RETURN_MAIN));
        assertEquals(MainActivity.class, LoginActivity.next("lixo"));
    }

    /** Item "Amigos" do drawer ou a FriendsActivity mandando para o login → abre Amigos. */
    @Test public void friendsOriginOpensFriends() {
        assertEquals(FriendsActivity.class, LoginActivity.next(LoginActivity.RETURN_FRIENDS));
    }

    /** Linha "Conta" das Configurações → só fecha o Login (a tela de baixo atualiza no onResume). */
    @Test public void backOriginJustCloses() {
        assertNull(LoginActivity.next(LoginActivity.RETURN_BACK));
    }
}
