package com.nacos.ncg.shared.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置内容编解码器（MUST-15 / 详细设计 v1.4 §4.5 ConfigContentCodec）：
 * 四种固化格式 PROPERTIES / YAML / JSON / TEXT 的解析（key+值条目）与序列化。
 * - PROPERTIES：按行 key=value 或 key:value、# ! 注释、行尾 \ 续行；写回保留注释与顺序；
 * - YAML：层级→点路径、数组下标 [n]；写回丢注释（[Assumption,TBD]，契约允许）；
 * - JSON：标准 JSON 对象，展开规则同 YAML；
 * - TEXT：不解析，items 为空，content 为原文，仅整文件编辑，原样写回。
 * 解析失败返回 ParseError（line/column/message），调用方浏览态只读展示、写路径抛 CR-422-001。
 * 敏感项识别基于展开后的 key 启发（[Assumption,TBD]：password/secret/token 等关键词）。
 */
public final class ConfigContentCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigContentCodec() {
    }

    /** 解析错误（浏览态返回，写路径转 CR-422-001）。line/column 为 1 基，取不到位置时为 0。 */
    public record ParseError(int line, int column, String message) {
    }

    /** 解析条目。valueType ∈ STRING/NUMBER/BOOLEAN/JSON；sensitive 由展开后的 key 启发判定。 */
    public record Item(String key, String value, String valueType, boolean sensitive) {
    }

    /** 解析结果：items（TEXT 为空列表）、content（整文件原文）、parseError（失败时非空）。 */
    public record Parsed(List<Item> items, String content, ParseError parseError) {
    }

    /** 条目变更：value 为 null 或空串表示删除该 key。 */
    public record ItemUpdate(String key, String value) {
    }

    // ==================== 格式判定 ====================

    /** 按 dataId 后缀判定固化格式：.properties→PROPERTIES；.yaml/.yml→YAML；.json→JSON；其余（含 .txt）→TEXT。 */
    public static String formatOfDataId(String dataId) {
        String d = dataId == null ? "" : dataId.toLowerCase(Locale.ROOT);
        if (d.endsWith(".properties")) {
            return "PROPERTIES";
        }
        if (d.endsWith(".yaml") || d.endsWith(".yml")) {
            return "YAML";
        }
        if (d.endsWith(".json")) {
            return "JSON";
        }
        return "TEXT";
    }

    /**
     * 后缀是否为可写已知格式：.properties/.yaml/.yml/.json/.txt 为 true；
     * 其他未知后缀（.ini/.conf 等）false——写路径拒写 CR-415-001（MUST-NOT-11）。
     */
    public static boolean isKnownSuffix(String dataId) {
        String d = dataId == null ? "" : dataId.toLowerCase(Locale.ROOT);
        return d.endsWith(".properties") || d.endsWith(".yaml") || d.endsWith(".yml")
                || d.endsWith(".json") || d.endsWith(".txt");
    }

    /** 敏感键启发式（[Assumption,TBD]）：key 小写后含关键词即敏感。 */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        String[] words = {"password", "passwd", "pwd", "secret", "token", "credential",
                "accesskey", "privatekey", "apikey", "auth"};
        for (String w : words) {
            if (k.contains(w)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 解析 ====================

    /** 按格式解析。永不抛异常：失败返回 Parsed(空 items, 原文, parseError)。 */
    public static Parsed parse(String format, String rawContent) {
        String content = rawContent == null ? "" : rawContent;
        if ("TEXT".equals(format) || format == null) {
            return new Parsed(List.of(), content, null);
        }
        try {
            switch (format) {
                case "PROPERTIES":
                    return parseProperties(content);
                case "YAML":
                    return parseStructuredYaml(content);
                case "JSON":
                    return parseJson(content);
                default:
                    return new Parsed(List.of(), content, null);
            }
        } catch (NcgException e) {
            throw e;
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            // SnakeYAML 解析失败：尽量带行/列号（TC-11）
            int line = 0;
            int col = 0;
            org.yaml.snakeyaml.error.MarkedYAMLException marked =
                    e instanceof org.yaml.snakeyaml.error.MarkedYAMLException m ? m : null;
            if (marked != null && marked.getProblemMark() != null) {
                line = marked.getProblemMark().getLine() + 1;
                col = marked.getProblemMark().getColumn() + 1;
            }
            return new Parsed(List.of(), content, new ParseError(line, col,
                    e.getMessage() == null ? "parse failure" : e.getMessage()));
        } catch (RuntimeException e) {
            return new Parsed(List.of(), content, new ParseError(0, 0, e.getMessage() == null ? "parse failure" : e.getMessage()));
        }
    }

    private static Parsed parseProperties(String content) {
        List<Item> items = new ArrayList<>();
        List<String> lines = splitKeepEnds(content);
        int i = 0;
        while (i < lines.size()) {
            String logical = lines.get(i);
            // 续行：行尾 \（忽略行尾空白后）
            while (rstripNewline(logical).endsWith("\\") && i + 1 < lines.size()) {
                i++;
                logical = rstripNewline(logical) + lines.get(i);
            }
            i++;
            String trimmed = logical.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int sep = indexOfSeparator(logical);
            if (sep < 0) {
                // 无分隔符的裸行：按 Java properties 语义视为 key=空值（浏览不阻断）
                items.add(new Item(trimmed, "", "STRING", isSensitiveKey(trimmed)));
                continue;
            }
            String key = logical.substring(0, sep).trim();
            String value = logical.substring(sep + 1).trim();
            items.add(new Item(key, value, valueTypeOf(value), isSensitiveKey(key)));
        }
        return new Parsed(items, content, null);
    }

    /** 分隔符：第一个未转义的 = 或 :（\= 与 \: 不作分隔；properties 语义取先出现者）。 */
    private static int indexOfSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' ) {
                i++; // 跳过被转义字符
                continue;
            }
            if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static Parsed parseStructuredYaml(String content) {
        Object root = new Yaml().load(content);
        List<Item> items = new ArrayList<>();
        flatten(root, "", items);
        return new Parsed(items, content, null);
    }

    private static Parsed parseJson(String content) {
        try {
            Object root = MAPPER.readValue(content, Object.class);
            List<Item> items = new ArrayList<>();
            flatten(root, "", items);
            return new Parsed(items, content, null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            int line = e.getLocation() != null ? e.getLocation().getLineNr() : 0;
            int col = e.getLocation() != null ? e.getLocation().getColumnNr() : 0;
            return new Parsed(List.of(), content,
                    new ParseError(line, col, e.getOriginalMessage() == null ? "parse failure" : e.getOriginalMessage()));
        }
    }

    // ==================== 展开 ====================

    @SuppressWarnings("unchecked")
    private static void flatten(Object node, String prefix, List<Item> out) {
        if (node instanceof Map) {
            ((Map<String, Object>) node).forEach((k, v) ->
                    flatten(v, prefix.isEmpty() ? k : prefix + "." + k, out));
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            for (int i = 0; i < list.size(); i++) {
                flatten(list.get(i), prefix + "[" + i + "]", out);
            }
        } else {
            String value = leafToString(node);
            out.add(new Item(prefix, value, valueTypeOf(value), isSensitiveKey(prefix)));
        }
    }

    private static String leafToString(Object node) {
        if (node == null) {
            return "null";
        }
        if (node instanceof String) {
            return (String) node;
        }
        return String.valueOf(node);
    }

    /** valueType 推断：true/false→BOOLEAN；数值字面量→NUMBER；{ 或 [ 开头→JSON；其余 STRING。 */
    public static String valueTypeOf(String value) {
        if (value == null) {
            return "STRING";
        }
        String t = value.trim();
        if (t.equals("true") || t.equals("false")) {
            return "BOOLEAN";
        }
        if (t.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) {
            return "NUMBER";
        }
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            return "JSON";
        }
        return "STRING";
    }

    // ==================== 应用条目变更 ====================

    /**
     * 把条目变更应用到基线内容返回新的整文件内容（baseContent 为 null 视为空文档）。
     * 基线解析失败抛 CR-422-001（details 含 line/column，message 含「第 N 行」）；
     * TEXT 格式带条目变更抛 CR-400-001（TEXT 仅整文件 content 编辑）。
     * value 为 null/空串删除该 key；PROPERTIES 保留注释与顺序（替换原行位置），YAML/JSON 重建结构（丢注释，[Assumption,TBD]）。
     */
    public static String applyItems(String format, String baseContent, List<ItemUpdate> updates) {
        List<ItemUpdate> list = updates == null ? List.of() : updates;
        if ("TEXT".equals(format) || format == null) {
            if (!list.isEmpty()) {
                throw new NcgException(NcgErrorCode.CR_400_001,
                        "items are not supported for TEXT format, use content (整文件编辑)");
            }
            return baseContent == null ? "" : baseContent;
        }
        if (list.isEmpty()) {
            return baseContent == null ? "" : baseContent;
        }
        if ("PROPERTIES".equals(format)) {
            return applyProperties(baseContent == null ? "" : baseContent, list);
        }
        Object root = parseTreeOr422(format, baseContent == null ? "" : baseContent);
        for (ItemUpdate u : list) {
            if (u.key() == null || u.key().isBlank()) {
                throw new NcgException(NcgErrorCode.CR_400_001, "item key must not be empty");
            }
            if (u.value() == null || u.value().isEmpty()) {
                removePath(root, u.key());
            } else {
                root = setPath(root, u.key(), coerce(u.value()));
            }
        }
        return dump(format, root);
    }

    /** 由条目列表重建整文件内容（YAML/JSON 嵌套重建；PROPERTIES 按传入顺序 key=value；TEXT 不支持）。 */
    public static String serialize(String format, List<Item> items) {
        if ("TEXT".equals(format) || format == null) {
            throw new IllegalArgumentException("TEXT format has no item serialization");
        }
        if ("PROPERTIES".equals(format)) {
            StringBuilder sb = new StringBuilder();
            for (Item it : items) {
                sb.append(it.key()).append('=').append(it.value()).append('\n');
            }
            return sb.toString();
        }
        Object root = ("YAML".equals(format)) ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>();
        for (Item it : items) {
            root = setPath(root, it.key(), coerce(it.value()));
        }
        return dump(format, root);
    }

    // ==================== PROPERTIES 行级编辑 ====================

    private static String applyProperties(String content, List<ItemUpdate> updates) {
        List<String> lines = splitKeepEnds(content);
        // 逻辑条目：startIndex..endIndex（含续行），key
        record Entry(String key, int start, int end) {
        }
        List<Entry> entries = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            int start = i;
            String logical = lines.get(i);
            while (rstripNewline(logical).endsWith("\\") && i + 1 < lines.size()) {
                i++;
                logical = rstripNewline(logical) + lines.get(i);
            }
            int end = i;
            i++;
            String trimmed = logical.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int sep = indexOfSeparator(logical);
            String key = sep < 0 ? trimmed : logical.substring(0, sep).trim();
            entries.add(new Entry(key, start, end));
        }
        StringBuilder out = new StringBuilder();
        boolean[] written = new boolean[lines.size()];
        Map<String, String> appends = new LinkedHashMap<>();
        for (ItemUpdate u : updates) {
            if (u.key() == null || u.key().isBlank()) {
                throw new NcgException(NcgErrorCode.CR_400_001, "item key must not be empty");
            }
            Entry hit = null;
            for (Entry e : entries) {
                if (e.key().equals(u.key())) {
                    hit = e;
                    break;
                }
            }
            if (hit != null) {
                if (u.value() == null || u.value().isEmpty()) {
                    for (int l = hit.start(); l <= hit.end(); l++) {
                        written[l] = true; // 删除整条（含续行）
                    }
                } else {
                    for (int l = hit.start(); l <= hit.end(); l++) {
                        written[l] = true;
                    }
                    appends.put(u.key(), u.value()); // 保持简单：替换为单行（注释行为整行注释，不丢）
                    // 位置保留：在原首行位置输出
                    lines.set(hit.start(), u.key() + "=" + u.value() + "\n");
                    written[hit.start()] = false;
                }
            } else if (u.value() != null && !u.value().isEmpty()) {
                appends.put(u.key(), u.value());
            }
        }
        for (int l = 0; l < lines.size(); l++) {
            if (!written[l]) {
                out.append(lines.get(l));
            }
        }
        for (Map.Entry<String, String> e : appends.entrySet()) {
            // 已在原位写过的不再追加
            if (out.toString().contains(e.getKey() + "=")) {
                continue;
            }
            if (out.length() > 0 && !out.toString().endsWith("\n")) {
                out.append('\n');
            }
            out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return out.toString();
    }

    // ==================== 结构化（YAML/JSON）路径编辑 ====================

    private static Object parseTreeOr422(String format, String content) {
        try {
            if ("YAML".equals(format)) {
                Object root = new Yaml().load(content);
                return root == null ? new LinkedHashMap<String, Object>() : root;
            }
            if (content.isBlank()) {
                return new LinkedHashMap<String, Object>();
            }
            return MAPPER.readValue(content, Object.class);
        } catch (MarkedYAMLException e) {
            int line = e.getProblemMark() == null ? 0 : e.getProblemMark().getLine() + 1;
            int col = e.getProblemMark() == null ? 0 : e.getProblemMark().getColumn() + 1;
            throw new NcgException(NcgErrorCode.CR_422_001, "配置内容解析失败（第 " + line + " 行）",
                    Map.of("line", line, "column", col, "message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            int line = 0;
            int col = 0;
            Throwable t = e;
            while (t != null) {
                if (t instanceof com.fasterxml.jackson.core.JsonProcessingException jpe) {
                    if (jpe.getLocation() != null) {
                        line = jpe.getLocation().getLineNr();
                        col = jpe.getLocation().getColumnNr();
                    }
                    break;
                }
                t = t.getCause();
            }
            throw new NcgException(NcgErrorCode.CR_422_001, "配置内容解析失败（第 " + line + " 行）",
                    Map.of("line", line, "column", col, "message", String.valueOf(e.getMessage())));
        }
    }

    private static String dump(String format, Object root) {
        if ("JSON".equals(format)) {
            try {
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new NcgException(NcgErrorCode.SYS_500_001, "json serialize failure");
            }
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        return new Yaml(options).dump(root);
    }

    /** 把 "a.b[0].c" 拆为段：字符串段（Map 键）与整数段（List 下标）。 */
    private static List<Object> splitPath(String key) {
        List<Object> segs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.') {
                if (cur.length() > 0) {
                    segs.add(cur.toString());
                    cur.setLength(0);
                }
            } else if (c == '[') {
                if (cur.length() > 0) {
                    segs.add(cur.toString());
                    cur.setLength(0);
                }
                int close = key.indexOf(']', i);
                if (close < 0) {
                    throw new NcgException(NcgErrorCode.CR_400_001, "invalid item key: " + key);
                }
                segs.add(Integer.parseInt(key.substring(i + 1, close)));
                i = close;
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            segs.add(cur.toString());
        }
        if (segs.isEmpty()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "invalid item key: " + key);
        }
        return segs;
    }

    @SuppressWarnings("unchecked")
    private static Object setPath(Object root, String key, Object value) {
        List<Object> segs = splitPath(key);
        if (segs.get(0) instanceof Integer) {
            root = ensureList(root);
        } else {
            root = ensureMap(root);
        }
        Object cursor = root;
        for (int i = 0; i < segs.size() - 1; i++) {
            Object seg = segs.get(i);
            Object next = segs.get(i + 1);
            Object child;
            if (seg instanceof Integer idx) {
                List<Object> list = (List<Object>) cursor;
                while (list.size() <= idx) {
                    list.add(null);
                }
                child = list.get(idx);
                if (child == null || !(next instanceof Integer ? child instanceof List : child instanceof Map)) {
                    child = next instanceof Integer ? new ArrayList<>() : new LinkedHashMap<String, Object>();
                    list.set(idx, child);
                }
            } else {
                Map<String, Object> map = (Map<String, Object>) cursor;
                child = map.get(String.valueOf(seg));
                if (child == null || !(next instanceof Integer ? child instanceof List : child instanceof Map)) {
                    child = next instanceof Integer ? new ArrayList<>() : new LinkedHashMap<String, Object>();
                    map.put(String.valueOf(seg), child);
                }
            }
            cursor = child;
        }
        Object last = segs.get(segs.size() - 1);
        if (last instanceof Integer idx) {
            List<Object> list = (List<Object>) cursor;
            while (list.size() <= idx) {
                list.add(null);
            }
            list.set(idx, value);
        } else {
            ((Map<String, Object>) cursor).put(String.valueOf(last), value);
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static void removePath(Object root, String key) {
        List<Object> segs = splitPath(key);
        Object cursor = root;
        for (int i = 0; i < segs.size() - 1; i++) {
            Object seg = segs.get(i);
            if (cursor == null) {
                return;
            }
            cursor = seg instanceof Integer idx
                    ? (cursor instanceof List && ((List<Object>) cursor).size() > idx ? ((List<Object>) cursor).get(idx) : null)
                    : (cursor instanceof Map ? ((Map<String, Object>) cursor).get(String.valueOf(seg)) : null);
        }
        if (cursor == null) {
            return;
        }
        Object last = segs.get(segs.size() - 1);
        if (last instanceof Integer idx) {
            if (cursor instanceof List && ((List<Object>) cursor).size() > idx) {
                ((List<Object>) cursor).set(idx, null);
            }
        } else if (cursor instanceof Map) {
            ((Map<String, Object>) cursor).remove(String.valueOf(last));
        }
    }

    private static Object ensureMap(Object root) {
        return root instanceof Map ? root : new LinkedHashMap<String, Object>();
    }

    private static Object ensureList(Object root) {
        return root instanceof List ? root : new ArrayList<>();
    }

    /** 字符串值按字面量还原类型（更新值从 UI 传入为字符串）。 */
    private static Object coerce(String value) {
        String t = value.trim();
        if (t.equals("true")) {
            return Boolean.TRUE;
        }
        if (t.equals("false")) {
            return Boolean.FALSE;
        }
        if (t.matches("-?\\d+")) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if (t.matches("-?\\d+\\.\\d+([eE][-+]?\\d+)?")) {
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }

    // ==================== 小工具 ====================

    /** 按行切分并保留行尾换行（便于逐行重建）。 */
    private static List<String> splitKeepEnds(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(content.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    private static String rstripNewline(String s) {
        String r = s;
        while (r.endsWith("\n") || r.endsWith("\r")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
