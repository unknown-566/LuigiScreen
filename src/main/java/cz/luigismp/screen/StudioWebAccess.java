package cz.luigismp.screen;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class StudioWebAccess {

    private StudioWebAccess() {
    }

    static boolean isLoopbackBind(String bind) {
        String normalized = bind == null ? "" : bind.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank()
                || normalized.equals("127.0.0.1")
                || normalized.equals("localhost")
                || normalized.equals("::1");
    }

    static boolean isWildcardBind(String bind) {
        String normalized = bind == null ? "" : bind.trim();
        return normalized.equals("0.0.0.0") || normalized.equals("::");
    }

    static String url(String host, int port) {
        String value = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        if (value.contains(":") && !value.startsWith("[")) {
            value = "[" + value + "]";
        }
        return "http://" + value + ":" + port;
    }

    static List<Link> loginLinks(String publicUrl, String bind, int port, String token) {
        String suffix = "/login?token=" + token;
        if (publicUrl != null && !publicUrl.isBlank()) {
            return List.of(new Link("Public", publicUrl + suffix, true));
        }
        List<Link> result = new ArrayList<>();
        if (isWildcardBind(bind)) {
            List<String> hosts = lanHosts();
            for (int index = 0; index < hosts.size(); index++) {
                result.add(new Link(index == 0 ? "LAN" : "LAN " + (index + 1),
                        url(hosts.get(index), port) + suffix, index == 0));
            }
            result.add(new Link("Server PC", url("127.0.0.1", port) + suffix,
                    result.isEmpty()));
            return result;
        }
        String host = isLoopbackBind(bind) ? "127.0.0.1" : bind;
        result.add(new Link(isLoopbackBind(bind) ? "Server PC" : "Configured host",
                url(host, port) + suffix, true));
        if (!isLoopbackBind(bind)) {
            result.add(new Link("Server PC", url("127.0.0.1", port) + suffix,
                    false));
        }
        return result;
    }

    static List<String> lanHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        try {
            List<NetworkInterface> interfaces = NetworkInterface.networkInterfaces()
                    .filter(StudioWebAccess::usableInterface)
                    .sorted(Comparator.comparing(NetworkInterface::getName))
                    .toList();
            for (NetworkInterface networkInterface : interfaces) {
                networkInterface.inetAddresses()
                        .filter(StudioWebAccess::usableAddress)
                        .sorted(Comparator.comparingInt(StudioWebAccess::addressRank)
                                .thenComparing(InetAddress::getHostAddress))
                        .map(InetAddress::getHostAddress)
                        .map(StudioWebAccess::withoutZone)
                        .forEach(hosts::add);
            }
        } catch (SocketException ignored) {
            addLocalHostFallback(hosts);
        }
        if (hosts.isEmpty()) {
            addLocalHostFallback(hosts);
        }
        return List.copyOf(hosts);
    }

    private static void addLocalHostFallback(Set<String> hosts) {
        try {
            InetAddress local = InetAddress.getLocalHost();
            if (usableAddress(local)) {
                hosts.add(withoutZone(local.getHostAddress()));
            }
        } catch (UnknownHostException ignored) {
        }
    }

    private static boolean usableInterface(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp()
                    && !networkInterface.isLoopback()
                    && !networkInterface.isVirtual();
        } catch (SocketException ignored) {
            return false;
        }
    }

    private static boolean usableAddress(InetAddress address) {
        return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isMulticastAddress()
                && !(address instanceof Inet6Address inet6
                && inet6.isLinkLocalAddress());
    }

    private static int addressRank(InetAddress address) {
        if (address instanceof Inet4Address && address.isSiteLocalAddress()) return 0;
        if (address instanceof Inet4Address) return 1;
        if (address.isSiteLocalAddress()) return 2;
        return 3;
    }

    private static String withoutZone(String host) {
        int zone = host.indexOf('%');
        return zone < 0 ? host : host.substring(0, zone);
    }

    record Link(String label, String url, boolean primary) {
    }
}
