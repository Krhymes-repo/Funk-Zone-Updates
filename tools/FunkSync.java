// FunkSync: brings a Funk Zone server folder in line with the live update manifest, without starting Minecraft.
// Run (Unraid):  docker run --rm -v /mnt/gameservers/appdata/funkzone:/data --entrypoint java itzg/minecraft-server:java21 /data/FunkSync.java
// Add "--dry" after the .java path to only list what it would change.
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

public class FunkSync {
    static final String MANIFEST = "https://raw.githubusercontent.com/Krhymes-repo/Funk-Zone-Updates/main/manifest.json";

    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        String man = MANIFEST + "?t=" + System.currentTimeMillis();
        for (String x : a) if (x.startsWith("--manifest=")) man = x.substring(11);
        boolean dry = Arrays.asList(a).contains("--dry");
        Path data = Path.of(a.length > 0 && !a[0].startsWith("--") ? a[0] : "/data").toAbsolutePath().normalize();
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
        String body = man.startsWith("http") ? http.send(req(man), HttpResponse.BodyHandlers.ofString()).body() : Files.readString(Path.of(man));
        Map<String, Object> m = (Map<String, Object>) new Json(body).value();
        String version = (String) m.get("version");
        System.out.println("Funk Zone manifest " + version + " -> " + data + (dry ? "  (dry run)" : ""));
        int got = 0, ok = 0, gone = 0;
        Map<String, String> installed = new LinkedHashMap<>();
        for (Object o : (List<Object>) m.get("files")) {
            Map<String, Object> f = (Map<String, Object>) o;
            String path = (String) f.get("path"), side = (String) f.getOrDefault("side", "both"), mode = (String) f.getOrDefault("mode", "replace");
            if ("client".equals(side)) continue;
            Path t = safe(data, path);
            if (t == null) continue;
            String want = ((String) f.get("sha1")).toLowerCase();
            boolean exists = Files.exists(t);
            if (exists && ("once".equals(mode) || sha1(t).equals(want))) { ok++; installed.put(path, want); continue; }
            System.out.println((exists ? "  replace  " : "  add      ") + path);
            got++;
            installed.put(path, want);
            if (dry) continue;
            Path tmp = t.resolveSibling(t.getFileName() + ".funksync");
            Files.createDirectories(t.getParent());
            HttpResponse<InputStream> r = http.send(req((String) f.get("url")), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("download failed (" + r.statusCode() + ") for " + path);
            try (InputStream in = r.body()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
            if (!sha1(tmp).equals(want)) { Files.deleteIfExists(tmp); throw new IOException("checksum mismatch for " + path); }
            Files.move(tmp, t, StandardCopyOption.REPLACE_EXISTING);
        }
        for (Object o : (List<Object>) m.getOrDefault("remove", List.of())) {
            Path t = safe(data, (String) o);
            if (t != null && Files.exists(t)) { System.out.println("  remove   " + o); gone++; if (!dry) Files.delete(t); }
        }
        if (!dry) {
            // tell the in-game updater what is installed, so it doesn't fetch it all again
            Path work = data.resolve(".funk_update");
            Files.createDirectories(work);
            StringBuilder sb = new StringBuilder("{");
            for (var e : installed.entrySet()) sb.append(sb.length() > 1 ? "," : "").append(q(e.getKey())).append(':').append(q(e.getValue()));
            Files.writeString(work.resolve("installed.json"), sb.append('}').toString());
            Path cfg = data.resolve("config/funkfrontier-updater.json");
            if (Files.exists(cfg)) {
                String s = Files.readString(cfg).replaceAll("\"installed\"\\s*:\\s*\"[^\"]*\"", "\"installed\": " + q(version));
                Files.writeString(cfg, s);
            }
        }
        System.out.printf("Done: %d already right, %d downloaded, %d removed.%n", ok, got, gone);
    }

    static HttpRequest req(String u) {
        return HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofMinutes(10)).header("User-Agent", "FunkZone-Updater").GET().build();
    }

    static Path safe(Path data, String rel) {
        if (rel == null || rel.isEmpty() || rel.contains("..")) return null;
        Path p = data.resolve(rel).normalize();
        return p.startsWith(data) ? p : null;
    }

    static String sha1(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** just enough JSON for the manifest */
    static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = str(); ws(); i++; m.put(k, value()); ws(); if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') return str();
            int st = i;
            while (i < s.length() && ",]} \n\r\t".indexOf(s.charAt(i)) < 0) i++;
            String w = s.substring(st, i);
            return switch (w) { case "true" -> Boolean.TRUE; case "false" -> Boolean.FALSE; case "null" -> null; default -> Double.parseDouble(w); };
        }
        String str() {
            StringBuilder b = new StringBuilder(); i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') { char e = s.charAt(i++);
                    switch (e) { case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r'); case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; } default -> b.append(e); } }
                else b.append(c);
            }
        }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    }
}
