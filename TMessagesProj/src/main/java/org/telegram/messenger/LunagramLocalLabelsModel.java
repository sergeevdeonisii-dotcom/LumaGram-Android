package org.telegram.messenger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Bounded metadata only. This format is always encrypted by the owner-scoped store. */
public final class LunagramLocalLabelsModel {
    public static final int LIMIT = 256;
    public static final class Label {
        public final String name, group;
        public Label(String name, String group) {
            this.name = clean(name, 80); this.group = clean(group, 40);
        }
        public boolean empty() { return name.isEmpty() && group.isEmpty(); }
    }
    public static final Label EMPTY = new Label("", "");
    private final LinkedHashMap<String, Label> entries = new LinkedHashMap<>();
    /** Detached immutable snapshot; labels themselves only contain final strings. */
    Map<String, Label> snapshot() { return Collections.unmodifiableMap(new LinkedHashMap<>(entries)); }
    public Label get(String key) { checkKey(key); Label label = entries.get(key); return label == null ? EMPTY : label; }
    public void put(String key, Label label) {
        checkKey(key);
        if (label == null || key.startsWith("a:") && !label.group.isEmpty()) throw new IllegalArgumentException("Invalid label");
        if (label.empty()) { entries.remove(key); return; }
        if (!entries.containsKey(key)) {
            int count = 0;
            for (String existing : entries.keySet()) if (existing.charAt(0) == key.charAt(0)) count++;
            if (count >= LIMIT) throw new IllegalArgumentException("Label limit");
        }
        entries.put(key, label);
    }
    public static String aliasKey(long dialog) {
        if (dialog >= 0 || dialog == Long.MIN_VALUE) throw new IllegalArgumentException("Group or channel only");
        return "a:" + dialog;
    }
    /** Length-prefix avoids ambiguous concatenations; no connection data is retained. */
    public static String proxyKey(String... identity) {
        if (identity == null || identity.length != 6) throw new IllegalArgumentException("Proxy identity");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : identity) {
                if (part == null || part.length() > 8192) throw new IllegalArgumentException("Proxy identity");
                byte[] value = part.getBytes(StandardCharsets.UTF_8);
                digest.update((byte)(value.length >>> 24)); digest.update((byte)(value.length >>> 16));
                digest.update((byte)(value.length >>> 8)); digest.update((byte)value.length); digest.update(value);
            }
            return "p:" + hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    public static String clean(String value, int max) {
        if (value == null) throw new IllegalArgumentException("Missing label");
        String result = value.trim();
        if (result.codePointCount(0, result.length()) > max) throw new IllegalArgumentException("Label too long");
        for (int i = 0; i < result.length();) {
            int c = result.codePointAt(i);
            if (Character.isISOControl(c) || c == 0x061c || c == 0x200e || c == 0x200f
                    || c >= 0x202a && c <= 0x202e || c >= 0x2066 && c <= 0x2069
                    || c >= 0xd800 && c <= 0xdfff) throw new IllegalArgumentException("Invalid label character");
            i += Character.charCount(c);
        }
        return result;
    }
    private static void checkKey(String key) {
        if (key == null || key.length() > 66) throw new IllegalArgumentException("Invalid label key");
        if (key.matches("p:[0-9a-f]{64}")) return;
        if (key.startsWith("a:")) {
            long id = Long.parseLong(key.substring(2));
            if (aliasKey(id).equals(key)) return;
        }
        throw new IllegalArgumentException("Invalid label key");
    }
    public String encode() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1); out.writeInt(entries.size());
        for (Map.Entry<String,Label> entry : entries.entrySet()) {
            out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue().name); out.writeUTF(entry.getValue().group);
        }
        out.flush(); String result = hex(bytes.toByteArray());
        if (result.length() > 300000) throw new IllegalArgumentException("Label data limit");
        return result;
    }
    public static LunagramLocalLabelsModel decode(String raw) throws Exception {
        LunagramLocalLabelsModel model = new LunagramLocalLabelsModel();
        if (raw == null || raw.isEmpty()) return model;
        if (raw.length() > 300000 || (raw.length() & 1) != 0) throw new IllegalArgumentException("Invalid label data");
        byte[] bytes = new byte[raw.length()/2];
        for (int i=0;i<bytes.length;i++) {
            int a=Character.digit(raw.charAt(i*2),16), b=Character.digit(raw.charAt(i*2+1),16);
            if (a<0 || b<0) throw new IllegalArgumentException("Invalid label data"); bytes[i]=(byte)(a*16+b);
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt()!=1) throw new IllegalArgumentException("Unknown label data version");
        int count=in.readInt(); if(count<0 || count>LIMIT*2) throw new IllegalArgumentException("Label data limit");
        for(int i=0;i<count;i++) {
            String key=in.readUTF(); Label label=new Label(in.readUTF(),in.readUTF());
            if(label.empty() || model.entries.containsKey(key)) throw new IllegalArgumentException("Duplicate/empty label data");
            model.put(key,label);
        }
        if(in.available()!=0) throw new IllegalArgumentException("Trailing label data");
        return model;
    }
    private static String hex(byte[] bytes) {
        char[] chars=new char[bytes.length*2],digits="0123456789abcdef".toCharArray();
        for(int i=0;i<bytes.length;i++){chars[i*2]=digits[(bytes[i]&255)>>>4];chars[i*2+1]=digits[bytes[i]&15];}
        return new String(chars);
    }
    private LunagramLocalLabelsModel() {}
}
