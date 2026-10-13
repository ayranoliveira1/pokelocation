package com.pokelocation.app.social.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pokelocation.app.R;
import com.pokelocation.app.social.net.Api;

import org.junit.Test;

import java.io.IOException;

public class ErrorsTest {
    @Test
    public void knownCodesMapToOwnMessage() {
        assertEquals(R.string.soc_err_invalid_credentials, Errors.message(new Api.Error(401, "invalid_credentials")));
        assertEquals(R.string.soc_err_email_taken, Errors.message(new Api.Error(409, "email_taken")));
        assertEquals(R.string.soc_err_weak_password, Errors.message(new Api.Error(400, "weak_password")));
        assertEquals(R.string.soc_err_invalid_email, Errors.message(new Api.Error(400, "invalid_email")));
        assertEquals(R.string.soc_err_rate_limited, Errors.message(new Api.Error(429, "rate_limited")));
        assertEquals(R.string.soc_err_code_not_found, Errors.message(new Api.Error(404, "code_not_found")));
        assertEquals(R.string.soc_err_self_request, Errors.message(new Api.Error(400, "self_request")));
        assertEquals(R.string.soc_err_blocked, Errors.message(new Api.Error(403, "blocked")));
        assertEquals(R.string.soc_err_already_friends, Errors.message(new Api.Error(409, "already_friends")));
        assertEquals(R.string.soc_err_unauthorized, Errors.message(new Api.Error(401, "unauthorized")));
    }

    @Test
    public void googleExpiredAndServerDownHaveOwnMessage() {
        assertEquals(R.string.soc_err_invalid_grant, Errors.message(new Api.Error(400, "invalid_grant")));
        assertEquals(R.string.soc_err_unavailable, Errors.message(new Api.Error(503, "unavailable")));
        assertEquals(R.string.soc_err_generic, Errors.message(new Api.Error(400, "google_failed")));
    }

    @Test
    public void accessDeniedIsUserCancel() {
        assertTrue(Errors.cancelled(new Api.Error(400, "access_denied")));
        assertFalse(Errors.cancelled(new Api.Error(400, "google_failed")));
        assertFalse(Errors.cancelled(new IOException()));
    }

    @Test
    public void unknownServerCodeIsGeneric() {
        assertEquals(R.string.soc_err_generic, Errors.message(new Api.Error(500, "internal")));
        assertEquals(R.string.soc_err_generic, Errors.message(new Api.Error(500, null)));
    }

    @Test
    public void plainIoIsNetworkAndOtherIsGeneric() {
        assertEquals(R.string.soc_err_network, Errors.message(new IOException("timeout")));
        assertEquals(R.string.soc_err_generic, Errors.message(new IllegalStateException()));
    }

    @Test
    public void keystoreFailureIsGenericNotNetwork() {
        IOException ks = new IOException("keystore", new java.security.GeneralSecurityException("busy"));
        assertEquals(R.string.soc_err_generic, Errors.message(ks));
    }

    @Test
    public void unauthorizedOnlyForThatCode() {
        assertTrue(Errors.unauthorized(new Api.Error(401, "unauthorized")));
        assertFalse(Errors.unauthorized(new Api.Error(401, "invalid_credentials")));
        assertFalse(Errors.unauthorized(new IOException()));
    }
}
