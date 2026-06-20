package cz.luigismp.screen;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

final class StudioJson {

    private StudioJson() {
    }

    static String write(Object value) {
        StringBuilder result = new StringBuilder(512);
        append(result, value);
        return result.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            quote(output, text);
        } else if (value instanceof Number number) {
            appendNumber(output, number);
        } else if (value instanceof Boolean bool) {
            output.append(bool);
        } else if (value instanceof Map<?, ?> map) {
            appendMap(output, map);
        } else if (value instanceof Collection<?> collection) {
            appendCollection(output, collection);
        } else if (value.getClass().isArray()) {
            appendArray(output, value);
        } else if (value instanceof Enum<?> enumeration) {
            quote(output, enumeration.name().toLowerCase(java.util.Locale.ROOT));
        } else {
            quote(output, value.toString());
        }
    }

    private static void appendNumber(StringBuilder output, Number number) {
        if (number instanceof Double value && !Double.isFinite(value)) {
            output.append('0');
            return;
        }
        if (number instanceof Float value && !Float.isFinite(value)) {
            output.append('0');
            return;
        }
        output.append(number);
    }

    private static void appendMap(StringBuilder output, Map<?, ?> map) {
        output.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) output.append(',');
            first = false;
            quote(output, String.valueOf(entry.getKey()));
            output.append(':');
            append(output, entry.getValue());
        }
        output.append('}');
    }

    private static void appendCollection(StringBuilder output, Collection<?> values) {
        output.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) output.append(',');
            first = false;
            append(output, value);
        }
        output.append(']');
    }

    private static void appendArray(StringBuilder output, Object array) {
        output.append('[');
        int length = Array.getLength(array);
        for (int index = 0; index < length; index++) {
            if (index > 0) output.append(',');
            append(output, Array.get(array, index));
        }
        output.append(']');
    }

    private static void quote(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
