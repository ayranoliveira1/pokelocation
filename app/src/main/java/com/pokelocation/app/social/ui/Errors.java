package com.pokelocation.app.social.ui;

import com.pokelocation.app.R;
import com.pokelocation.app.social.net.Api;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** Erro de Api → string. Api.Error.code conhecido tem mensagem própria; IOException comum é rede; Keystore e o resto, genérica. */
public final class Errors {
    private Errors() {}

    public static int message(Throwable t) {
        if (t instanceof Api.Error) {
            String code = ((Api.Error) t).code;
            if (code == null) return R.string.soc_err_generic;
            switch (code) {
                case "invalid_credentials": return R.string.soc_err_invalid_credentials;
                case "email_taken": return R.string.soc_err_email_taken;
                case "weak_password": return R.string.soc_err_weak_password;
                case "invalid_email": return R.string.soc_err_invalid_email;
                case "rate_limited": return R.string.soc_err_rate_limited;
                case "code_not_found": return R.string.soc_err_code_not_found;
                case "self_request": return R.string.soc_err_self_request;
                case "blocked": return R.string.soc_err_blocked;
                case "already_friends": return R.string.soc_err_already_friends;
                case "unauthorized": return R.string.soc_err_unauthorized;
                case "invalid_grant": return R.string.soc_err_invalid_grant;
                case "unavailable": return R.string.soc_err_unavailable;
                case "username_taken": return R.string.soc_err_username_taken;
                case "invalid_username": return R.string.soc_err_invalid_username;
                case "username_reserved": return R.string.soc_err_username_reserved;
                case "username_required": return R.string.soc_err_username_required;
                default: return R.string.soc_err_generic;
            }
        }
        // Keystore falhou agora (IOException com causa GeneralSecurityException): não é rede
        if (t instanceof IOException && !(t.getCause() instanceof GeneralSecurityException)) return R.string.soc_err_network;
        return R.string.soc_err_generic;
    }

    /** O próprio usuário cancelou no Google (callback error=access_denied): sem mensagem nenhuma. */
    static boolean cancelled(Throwable t) {
        return t instanceof Api.Error && "access_denied".equals(((Api.Error) t).code);
    }

    public static boolean usernameRequired(Throwable t) {
        return t instanceof Api.Error && "username_required".equals(((Api.Error) t).code);
    }

    /** No PUT do @nome o 429 é o limite de trocas por dia, não o de tentativas por minuto. */
    static int usernameMessage(Throwable t) {
        return t instanceof Api.Error && "rate_limited".equals(((Api.Error) t).code) ? R.string.soc_err_username_limit : message(t);
    }

    static int deleteMessage(Throwable t) {
        String c = t instanceof Api.Error ? ((Api.Error) t).code : null;
        if ("invalid_credentials".equals(c)) return R.string.soc_delete_wrong_password;
        if ("reauth_mismatch".equals(c)) return R.string.soc_delete_other_google;
        if ("reauth_required".equals(c)) return R.string.soc_delete_reauth_required;
        return message(t);
    }

    static boolean aboutUsername(Throwable t) {
        if (!(t instanceof Api.Error)) return false;
        String c = ((Api.Error) t).code;
        return "username_taken".equals(c) || "invalid_username".equals(c) || "username_reserved".equals(c);
    }

    /** Sessão morreu (a Api já limpou): a tela volta ao login. */
    public static boolean unauthorized(Throwable t) {
        return t instanceof Api.Error && ((Api.Error) t).endsSession();
    }
}
