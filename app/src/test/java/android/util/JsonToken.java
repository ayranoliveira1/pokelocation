package android.util;

/** Só para os testes JVM: o android.jar de teste não implementa o android.util.JsonToken. */
public enum JsonToken { BEGIN_ARRAY, END_ARRAY, BEGIN_OBJECT, END_OBJECT, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }
