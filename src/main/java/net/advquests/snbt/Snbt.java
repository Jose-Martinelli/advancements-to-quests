package net.advquests.snbt;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor/escritor minimo do formato SNBT usado pelo FTB Quests (FTB Library).
 * <p>
 * Modelo em Java puro, para o mod nao depender do FTB Library em tempo de compilacao:
 * <ul>
 *   <li>compound -> {@code Map<String, Object>} (LinkedHashMap, preserva a ordem)</li>
 *   <li>lista -> {@code List<Object>}</li>
 *   <li>string -> {@code String}; booleano -> {@code Boolean}</li>
 *   <li>numeros -> Byte (b), Short (s), Integer, Long (L), Float (f), Double (d)</li>
 *   <li>arrays -> byte[], int[], long[]</li>
 * </ul>
 */
public final class Snbt {
    private Snbt() {
    }

    public static void writeFile(Path path, Map<String, Object> root) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, write(root) + "\n", StandardCharsets.UTF_8);
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{ }");
                return;
            }
            sb.append("{\n");
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                indent(sb, depth + 1);
                writeKey(sb, e.getKey());
                sb.append(": ");
                write(sb, e.getValue(), depth + 1);
                sb.append('\n');
            }
            indent(sb, depth);
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[ ]");
                return;
            }
            boolean simple = list.stream().noneMatch(v -> v instanceof Map || v instanceof List);
            if (simple) {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(", ");
                    write(sb, list.get(i), depth + 1);
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            for (Object v : list) {
                indent(sb, depth + 1);
                write(sb, v, depth + 1);
                sb.append('\n');
            }
            indent(sb, depth);
            sb.append(']');
        } else if (value instanceof String s) {
            quote(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Byte b) {
            sb.append(b).append('b');
        } else if (value instanceof Short s) {
            sb.append(s).append('s');
        } else if (value instanceof Integer i) {
            sb.append(i);
        } else if (value instanceof Long l) {
            sb.append(l).append('L');
        } else if (value instanceof Float f) {
            sb.append(plain(f.doubleValue())).append('f');
        } else if (value instanceof Double d) {
            sb.append(plain(d)).append('d');
        } else if (value instanceof byte[] arr) {
            sb.append("[B;");
            for (int i = 0; i < arr.length; i++) sb.append(i == 0 ? " " : ", ").append(arr[i]).append('b');
            sb.append(']');
        } else if (value instanceof int[] arr) {
            sb.append("[I;");
            for (int i = 0; i < arr.length; i++) sb.append(i == 0 ? " " : ", ").append(arr[i]);
            sb.append(']');
        } else if (value instanceof long[] arr) {
            sb.append("[L;");
            for (int i = 0; i < arr.length; i++) sb.append(i == 0 ? " " : ", ").append(arr[i]).append('L');
            sb.append(']');
        } else if (value == null) {
            sb.append("\"\"");
        } else {
            throw new IllegalArgumentException("Tipo nao suportado em SNBT: " + value.getClass());
        }
    }

    private static String plain(double d) {
        if (!Double.isFinite(d)) return "0.0";
        String s = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        return s.contains(".") ? s : s + ".0";
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append('\t');
    }

    private static void writeKey(StringBuilder sb, String key) {
        if (!key.isEmpty() && isSimpleString(key)) {
            sb.append(key);
        } else {
            quote(sb, key);
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\f' -> sb.append("\\f");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    private static boolean isSimpleChar(char c) {
        return Character.isAlphabetic(c) || Character.isDigit(c) || c == '.' || c == '_' || c == '-' || c == '+';
    }

    private static boolean isSimpleString(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isSimpleChar(s.charAt(i))) return false;
        }
        return true;
    }

    /** Le um arquivo SNBT. Retorna null se nao existir ou nao for valido. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readFile(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            Object o = read(Files.readString(path, StandardCharsets.UTF_8));
            return o instanceof Map ? (Map<String, Object>) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static Object read(String text) {
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("//") && !t.startsWith("#")) buf.append(line);
            buf.append('\n');
        }
        return new Parser(buf.toString()).readRoot();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object readRoot() {
            return readValue(nextNonSpace());
        }

        private char next() {
            if (pos >= s.length()) throw new IllegalStateException("Fim inesperado do SNBT");
            return s.charAt(pos++);
        }

        private char nextNonSpace() {
            while (true) {
                char c = next();
                if (c > ' ') return c;
            }
        }

        private Object readValue(char first) {
            switch (first) {
                case '{':
                    return readCompound();
                case '[':
                    return readCollection();
                case '"':
                case '\'':
                    return readQuoted(first);
                default:
                    return parseWord(readWord(first));
            }
        }

        private Map<String, Object> readCompound() {
            Map<String, Object> map = new LinkedHashMap<>();
            while (true) {
                char c = nextNonSpace();
                if (c == '}') return map;
                if (c == ',') continue;
                String key = (c == '"' || c == '\'') ? readQuoted(c) : readWord(c);
                char sep = nextNonSpace();
                if (sep != ':' && sep != '=') {
                    throw new IllegalStateException("Esperado ':' depois de '" + key + "', veio '" + sep + "'");
                }
                map.put(key, readValue(nextNonSpace()));
            }
        }

        private Object readCollection() {
            int save = pos;
            char a = nextNonSpace();
            char b = nextNonSpace();
            if (b == ';' && "BbIiLl".indexOf(a) >= 0) {
                return readArray(Character.toLowerCase(a));
            }
            pos = save;
            List<Object> list = new ArrayList<>();
            while (true) {
                char c = nextNonSpace();
                if (c == ']') return list;
                if (c == ',') continue;
                list.add(readValue(c));
            }
        }

        private Object readArray(char type) {
            List<Number> values = new ArrayList<>();
            while (true) {
                char c = nextNonSpace();
                if (c == ']') break;
                if (c == ',') continue;
                Object v = readValue(c);
                if (!(v instanceof Number n)) throw new IllegalStateException("Array com valor nao numerico");
                values.add(n);
            }
            switch (type) {
                case 'b' -> {
                    byte[] r = new byte[values.size()];
                    for (int i = 0; i < r.length; i++) r[i] = values.get(i).byteValue();
                    return r;
                }
                case 'i' -> {
                    int[] r = new int[values.size()];
                    for (int i = 0; i < r.length; i++) r[i] = values.get(i).intValue();
                    return r;
                }
                default -> {
                    long[] r = new long[values.size()];
                    for (int i = 0; i < r.length; i++) r[i] = values.get(i).longValue();
                    return r;
                }
            }
        }

        private String readWord(char first) {
            StringBuilder sb = new StringBuilder().append(first);
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (isSimpleChar(c) || c == '∞') {
                    sb.append(c);
                    pos++;
                } else {
                    break;
                }
            }
            return sb.toString();
        }

        private String readQuoted(char stop) {
            StringBuilder sb = new StringBuilder();
            boolean escape = false;
            while (true) {
                char c = next();
                if (c == '\n') throw new IllegalStateException("String sem fechamento");
                if (escape) {
                    escape = false;
                    switch (c) {
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 'f' -> sb.append('\f');
                        default -> sb.append(c);
                    }
                } else if (c == '\\') {
                    escape = true;
                } else if (c == stop) {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
        }

        private static Object parseWord(String w) {
            switch (w) {
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                default:
                    break;
            }
            if (w.isEmpty()) return w;
            char last = w.charAt(w.length() - 1);
            String body = w.substring(0, w.length() - 1);
            try {
                switch (Character.toLowerCase(last)) {
                    case 'b':
                        if (isInteger(body)) return Byte.parseByte(body);
                        break;
                    case 's':
                        if (isInteger(body)) return Short.parseShort(body);
                        break;
                    case 'l':
                        if (isInteger(body)) return Long.parseLong(body);
                        break;
                    case 'f':
                        if (isDecimal(body)) return Float.parseFloat(body);
                        break;
                    case 'd':
                        if (isDecimal(body)) return Double.parseDouble(body);
                        break;
                    default:
                        break;
                }
                if (isInteger(w)) return Integer.parseInt(w);
                if (isDecimal(w)) return Double.parseDouble(w);
            } catch (NumberFormatException ignored) {
            }
            return w;
        }

        private static boolean isInteger(String s) {
            return s.matches("[+-]?\\d+");
        }

        private static boolean isDecimal(String s) {
            return s.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)");
        }
    }
}
