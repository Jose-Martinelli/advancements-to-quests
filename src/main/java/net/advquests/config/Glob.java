package net.advquests.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Padroes usados na config para casar IDs de advancements (ex.: "botania:main/root").
 * <ul>
 *   <li>{@code *} casa qualquer sequencia; {@code ?} casa um caractere</li>
 *   <li>Sem ':' e sem curinga, o padrao e tratado como namespace: "botania" == "botania:*"</li>
 * </ul>
 */
public final class Glob {
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private Glob() {
    }

    public static boolean matches(String pattern, String id) {
        if (pattern == null || pattern.isBlank()) return false;
        return CACHE.computeIfAbsent(pattern.trim(), Glob::compile).matcher(id).matches();
    }

    private static Pattern compile(String p) {
        if (p.indexOf(':') < 0 && p.indexOf('*') < 0 && p.indexOf('?') < 0) {
            p = p + ":*";
        }
        StringBuilder rx = new StringBuilder();
        for (char c : p.toCharArray()) {
            switch (c) {
                case '*' -> rx.append(".*");
                case '?' -> rx.append('.');
                default -> rx.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(rx.toString());
    }
}
