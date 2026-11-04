package com.circularsync.web;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

/**
 * Minimal streaming JSON writer, enough for the portal's API without a
 * third-party library. Commas are inserted automatically.
 *
 * <pre>
 * new JsonWriter().beginObject().field("id", "EXM-1").field("score", 86).endObject().toString()
 * </pre>
 */
final class JsonWriter {

    private final StringBuilder out = new StringBuilder(1024);
    /** One entry per open container: true until its first element is written. */
    private final Deque<Boolean> firstInScope = new ArrayDeque<>();
    private boolean afterName;

    JsonWriter beginObject() {
        beforeValue();
        out.append('{');
        firstInScope.push(Boolean.TRUE);
        return this;
    }

    JsonWriter endObject() {
        firstInScope.pop();
        out.append('}');
        return this;
    }

    JsonWriter beginArray() {
        beforeValue();
        out.append('[');
        firstInScope.push(Boolean.TRUE);
        return this;
    }

    JsonWriter endArray() {
        firstInScope.pop();
        out.append(']');
        return this;
    }

    JsonWriter name(String name) {
        separate();
        quote(name);
        out.append(':');
        afterName = true;
        return this;
    }

    JsonWriter value(String value) {
        beforeValue();
        if (value == null) {
            out.append("null");
        } else {
            quote(value);
        }
        return this;
    }

    JsonWriter value(long value) {
        beforeValue();
        out.append(value);
        return this;
    }

    JsonWriter value(double value) {
        beforeValue();
        out.append(Double.isFinite(value) ? Double.toString(value) : "null");
        return this;
    }

    JsonWriter value(boolean value) {
        beforeValue();
        out.append(value);
        return this;
    }

    JsonWriter field(String name, String value) {
        return name(name).value(value);
    }

    JsonWriter field(String name, long value) {
        return name(name).value(value);
    }

    JsonWriter field(String name, double value) {
        return name(name).value(value);
    }

    JsonWriter field(String name, boolean value) {
        return name(name).value(value);
    }

    JsonWriter field(String name, Collection<String> values) {
        name(name).beginArray();
        for (String value : values) {
            value(value);
        }
        return endArray();
    }

    @Override
    public String toString() {
        return out.toString();
    }

    private void beforeValue() {
        if (afterName) {
            afterName = false;
        } else {
            separate();
        }
    }

    private void separate() {
        if (firstInScope.isEmpty()) {
            return;
        }
        if (firstInScope.peek()) {
            firstInScope.pop();
            firstInScope.push(Boolean.FALSE);
        } else {
            out.append(',');
        }
    }

    private void quote(String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
