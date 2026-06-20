package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StudioJsonTest {

    @Test
    void writesNestedValuesAndEscapesUnsafeCharacters() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "line\n\"quoted\"");
        value.put("values", List.of(1, true, "x"));
        String json = StudioJson.write(value);

        assertEquals("{\"text\":\"line\\n\\\"quoted\\\"\",\"values\":[1,true,\"x\"]}", json);
    }

    @Test
    void replacesNonFiniteNumbersWithZero() {
        assertEquals("[0,0]", StudioJson.write(List.of(Double.NaN, Float.POSITIVE_INFINITY)));
    }
}
