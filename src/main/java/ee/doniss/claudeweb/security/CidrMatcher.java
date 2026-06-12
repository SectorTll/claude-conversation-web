package ee.doniss.claudeweb.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Matches a client IP against a fixed allowlist of CIDR ranges and bare addresses. Pure logic, no
 * Spring — unit-tested directly (cf. the hand-rolled guards in {@code ClaudeDataService}).
 *
 * <p>A bare address ({@code 127.0.0.1}, {@code ::1}) is treated as a full-length prefix. IPv4-mapped
 * IPv6 ({@code ::ffff:a.b.c.d}, which {@code getRemoteAddr()} can return on a dual-stack bind) is
 * normalised to its 4-byte IPv4 form before matching so it lines up with v4 rules. Only numeric IP
 * literals are accepted — never hostnames, so {@link InetAddress#getByName} never triggers DNS.
 */
public final class CidrMatcher {

    private record Rule(byte[] network, int prefixBits) {
    }

    private final List<Rule> rules;

    private CidrMatcher(List<Rule> rules) {
        this.rules = rules;
    }

    /** Parse the configured entries. Throws {@link IllegalArgumentException} on a malformed entry (fail-fast). */
    public static CidrMatcher parse(List<String> cidrs) {
        List<Rule> rules = new ArrayList<>();
        for (String raw : cidrs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            int slash = entry.indexOf('/');
            String addrPart = slash >= 0 ? entry.substring(0, slash) : entry;
            byte[] net = normalize(toBytes(addrPart));
            int maxBits = net.length * 8;
            int prefix;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid CIDR prefix in '" + entry + "'", e);
                }
                if (prefix < 0 || prefix > maxBits) {
                    throw new IllegalArgumentException("CIDR prefix out of range in '" + entry + "'");
                }
            } else {
                prefix = maxBits; // bare address → exact match
            }
            rules.add(new Rule(net, prefix));
        }
        return new CidrMatcher(List.copyOf(rules));
    }

    /** True if {@code remoteAddr} (a numeric literal, e.g. from {@code getRemoteAddr()}) falls in any rule. */
    public boolean matches(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        byte[] addr;
        try {
            addr = normalize(toBytes(remoteAddr.trim()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Rule r : rules) {
            if (inRange(addr, r)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inRange(byte[] addr, Rule rule) {
        if (addr.length != rule.network().length) {
            return false; // v4 never matches a genuine v6 rule once both are normalised
        }
        int fullBytes = rule.prefixBits() / 8;
        int remBits = rule.prefixBits() % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (addr[i] != rule.network()[i]) {
                return false;
            }
        }
        if (remBits > 0) {
            int mask = (0xFF << (8 - remBits)) & 0xFF;
            return (addr[fullBytes] & mask) == (rule.network()[fullBytes] & mask);
        }
        return true;
    }

    private static byte[] toBytes(String literal) {
        if (!isNumericIp(literal)) {
            throw new IllegalArgumentException("Not a numeric IP literal: '" + literal + "'");
        }
        try {
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP literal: '" + literal + "'", e);
        }
    }

    /** Collapse an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) to its 4-byte IPv4 form. */
    private static byte[] normalize(byte[] addr) {
        if (addr.length == 16 && isV4Mapped(addr)) {
            return new byte[] {addr[12], addr[13], addr[14], addr[15]};
        }
        return addr;
    }

    private static boolean isV4Mapped(byte[] a) {
        for (int i = 0; i < 10; i++) {
            if (a[i] != 0) {
                return false;
            }
        }
        return (a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF;
    }

    /** Accept only digits/dots (v4) or hex/colons (v6) — reject anything that could be a DNS name. */
    private static boolean isNumericIp(String s) {
        if (s.indexOf(':') < 0 && s.indexOf('.') < 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == '.' || c == ':' || c == '%';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
