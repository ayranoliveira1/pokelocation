package com.pokelocation.app.social.ui;

import static org.junit.Assert.assertEquals;

import com.pokelocation.app.R;
import com.pokelocation.app.social.crypto.SignalLoader;

import org.junit.Test;

import java.io.IOException;
import java.util.Locale;

public class SignalProgressTest {
    @Test
    public void mbHasOneDecimalInLocale() {
        assertEquals("3.0", SignalProgress.mb(3L << 20, Locale.US));
        assertEquals("1,5", SignalProgress.mb(3L << 19, Locale.forLanguageTag("pt-BR")));
        assertEquals("0.0", SignalProgress.mb(0, Locale.US));
    }

    @Test
    public void noSpaceHasOwnMessage() {
        assertEquals(R.string.soc_signal_no_space, SignalProgress.errorText(new IOException(SignalLoader.NO_SPACE)));
        assertEquals(R.string.soc_signal_failed, SignalProgress.errorText(new IOException("timeout")));
        assertEquals(R.string.soc_signal_failed, SignalProgress.errorText(new IOException()));
    }
}
