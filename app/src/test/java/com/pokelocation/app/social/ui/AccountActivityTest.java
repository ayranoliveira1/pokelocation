package com.pokelocation.app.social.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AccountActivityTest {
    @Test
    public void drawerItemOpensAccountOrLoginThatJustCloses() {
        assertEquals(AccountActivity.class, AccountActivity.entry(true));
        assertEquals(LoginActivity.class, AccountActivity.entry(false));
        assertNull(LoginActivity.next(AccountActivity.LOGIN_RETURN));
    }
}
