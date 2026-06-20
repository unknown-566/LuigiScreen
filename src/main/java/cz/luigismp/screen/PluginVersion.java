package cz.luigismp.screen;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PluginVersion implements Comparable<PluginVersion> {

    private static final Pattern VERSION = Pattern.compile(
            "^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    private final List<BigInteger> core;
    private final List<Identifier> preRelease;

    private PluginVersion(List<BigInteger> core, List<Identifier> preRelease) {
        this.core = core;
        this.preRelease = preRelease;
    }

    static Optional<PluginVersion> parse(String value) {
        String normalized = value == null ? "" : value.trim();
        Matcher matcher = VERSION.matcher(normalized);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        List<BigInteger> core = new ArrayList<>();
        for (String part : matcher.group(1).split("\\.")) {
            core.add(new BigInteger(part));
        }

        List<Identifier> preRelease = new ArrayList<>();
        String suffix = matcher.group(2);
        if (suffix != null) {
            for (String part : suffix.split("\\.")) {
                preRelease.add(Identifier.parse(part));
            }
        }
        return Optional.of(new PluginVersion(
                List.copyOf(core), List.copyOf(preRelease)));
    }

    @Override
    public int compareTo(PluginVersion other) {
        int coreSize = Math.max(core.size(), other.core.size());
        for (int index = 0; index < coreSize; index++) {
            BigInteger left = index < core.size() ? core.get(index) : BigInteger.ZERO;
            BigInteger right = index < other.core.size() ? other.core.get(index) : BigInteger.ZERO;
            int compared = left.compareTo(right);
            if (compared != 0) {
                return compared;
            }
        }

        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            if (preRelease.isEmpty() == other.preRelease.isEmpty()) {
                return 0;
            }
            return preRelease.isEmpty() ? 1 : -1;
        }

        int suffixSize = Math.max(preRelease.size(), other.preRelease.size());
        for (int index = 0; index < suffixSize; index++) {
            if (index >= preRelease.size()) {
                return -1;
            }
            if (index >= other.preRelease.size()) {
                return 1;
            }
            int compared = preRelease.get(index).compareTo(other.preRelease.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private record Identifier(String text, BigInteger number) implements Comparable<Identifier> {

        private static Identifier parse(String value) {
            try {
                return new Identifier(value, new BigInteger(value));
            } catch (NumberFormatException ignored) {
                return new Identifier(value.toLowerCase(Locale.ROOT), null);
            }
        }

        @Override
        public int compareTo(Identifier other) {
            if (number != null && other.number != null) {
                return number.compareTo(other.number);
            }
            if (number != null) {
                return -1;
            }
            if (other.number != null) {
                return 1;
            }
            return text.compareTo(other.text);
        }
    }
}
