// FunkRegen: wipes Homestead Wilds (overworld) chunks so they regenerate with the current mods, while keeping
//  - every Claim Flag claim (plus a margin), - the Commons, its Market annex and the Ranger post (a box around the Commons),
//  - the world spawn, - any chunk players have spent real time in (InhabitedTime), so unclaimed builds survive.
// The server must be STOPPED. Only the overworld (world/region, entities, poi) is touched.
// Usage: java -jar FunkRegen.jar /data [--dry] [--world=world] [--inhabited-min=30 (0 = off)] [--claim-margin=2] [--commons-radius=192]
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class FunkRegen {
    static int keepClaim, keepCommons, keepSpawn, keepInhabited, wiped, unreadable;

    public static void main(String[] a) throws Exception {
        Path data = Path.of(a.length > 0 && !a[0].startsWith("--") ? a[0] : "/data");
        String world = opt(a, "world", "world");
        boolean dry = Arrays.asList(a).contains("--dry");
        long inhabitedTicks = Long.parseLong(opt(a, "inhabited-min", "30")) * 60 * 20;
        int margin = Integer.parseInt(opt(a, "claim-margin", "2"));
        int commonsR = Integer.parseInt(opt(a, "commons-radius", "192"));
        Path w = data.resolve(world);
        if (!Files.isDirectory(w.resolve("region"))) throw new IOException("no overworld region folder at " + w.resolve("region"));
        if (Files.exists(w.resolve("session.lock")) && !dry) {
            // a running server keeps session.lock open; we can't tell from here, so just warn
            System.out.println("Make sure the server is stopped (docker stop FunkZone) before running without --dry.");
        }

        // ---- what to keep
        List<int[]> boxes = new ArrayList<>();   // chunk boxes [x1,z1,x2,z2,kind]
        Map<String, Object> hs = nbtFile(w.resolve("data/funkfrontier_homestead.dat"));
        Map<String, Object> hd = hs == null ? null : (Map<String, Object>) hs.get("data");
        int claims = 0;
        if (hd != null) {
            for (Object o : (List<Object>) hd.getOrDefault("claims", List.of())) {
                Map<String, Object> c = (Map<String, Object>) o;
                int ccx = num(c.get("ccx")), ccz = num(c.get("ccz")), h = num(c.get("size")) / 2 + margin;
                boxes.add(new int[]{ccx - h, ccz - h, ccx + h, ccz + h, 0});
                claims++;
            }
            Map<String, Object> misc = (Map<String, Object>) hd.get("misc");
            if (misc != null && misc.containsKey("ox")) {
                int cx = num(misc.get("ox")) + 32, cz = num(misc.get("oz")) + 32;
                boxes.add(new int[]{(cx - commonsR) >> 4, (cz - commonsR) >> 4, (cx + commonsR) >> 4, (cz + commonsR) >> 4, 1});
                System.out.printf("Commons centre %d, %d: keeping %d blocks around it%n", cx, cz, commonsR);
            } else System.out.println("WARNING: no Commons position found in funkfrontier_homestead.dat");
        } else System.out.println("WARNING: no funkfrontier_homestead.dat - no claims or Commons to keep");
        System.out.println("Claims kept: " + claims + " (each with a " + margin + "-chunk margin)");
        Map<String, Object> lvl = nbtFile(w.resolve("level.dat"));
        if (lvl != null && lvl.get("Data") instanceof Map<?, ?> d) {
            int sx = num(d.get("SpawnX")), sz = num(d.get("SpawnZ"));
            boxes.add(new int[]{(sx >> 4) - 4, (sz >> 4) - 4, (sx >> 4) + 4, (sz >> 4) + 4, 2});
        }

        // ---- go through the overworld region files
        List<Path> regions = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(w.resolve("region"), "r.*.*.mca")) { for (Path p : ds) regions.add(p); }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<Long, Integer> verdict = new HashMap<>();
        for (Path r : regions) {
            String[] p = r.getFileName().toString().split("\\.");
            int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
            boolean[] wipe = new boolean[1024];
            try (RandomAccessFile f = new RandomAccessFile(r.toFile(), "r")) {
                if (f.length() < 8192) continue;
                int[] loc = new int[1024];
                for (int i = 0; i < 1024; i++) loc[i] = f.readInt();
                for (int i = 0; i < 1024; i++) {
                    if (loc[i] == 0) continue;
                    int cx = rx * 32 + (i & 31), cz = rz * 32 + (i >> 5);
                    minX = Math.min(minX, cx); maxX = Math.max(maxX, cx); minZ = Math.min(minZ, cz); maxZ = Math.max(maxZ, cz);
                    int kind = -1;
                    for (int[] b : boxes) if (cx >= b[0] && cx <= b[2] && cz >= b[1] && cz <= b[3]) { kind = b[4]; break; }
                    if (kind < 0 && inhabitedTicks > 0) {
                        long inh;
                        try { inh = inhabited(f, loc[i], r.getParent(), cx, cz); } catch (Exception ex) { inh = Long.MAX_VALUE; unreadable++; }
                        if (inh >= inhabitedTicks) kind = 3;
                    }
                    switch (kind) { case 0 -> keepClaim++; case 1 -> keepCommons++; case 2 -> keepSpawn++; case 3 -> keepInhabited++; default -> { wiped++; wipe[i] = true; } }
                    verdict.put(((long) cx << 32) ^ (cz & 0xffffffffL), kind);
                }
            }
            if (!dry) for (String sub : new String[]{"region", "entities", "poi"}) wipeIn(w.resolve(sub).resolve(r.getFileName()), wipe, rx, rz);
        }
        System.out.printf("Chunks kept: %d in claims, %d at the Commons, %d at spawn, %d where players spent %d+ min%n",
                keepClaim, keepCommons, keepSpawn, keepInhabited, inhabitedTicks / 1200);
        if (unreadable > 0) System.out.println("(" + unreadable + " chunks couldn't be read and were kept to be safe)");
        System.out.println((dry ? "Would regenerate: " : "Regenerated (wiped): ") + wiped + " chunks");
        preview(data.resolve("regen-preview.png"), verdict, minX, minZ, maxX, maxZ);
        System.out.println("Map saved to " + data.resolve("regen-preview.png") + "  (green = claims, blue = Commons, yellow = player areas, grey = regenerates)");
    }

    static String opt(String[] a, String k, String d) {
        for (String x : a) if (x.startsWith("--" + k + "=")) return x.substring(k.length() + 3);
        return d;
    }

    static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    /** InhabitedTime of one chunk (stream-skips everything else) */
    static long inhabited(RandomAccessFile f, int loc, Path dir, int cx, int cz) throws IOException {
        long off = (long) (loc >>> 8) * 4096;
        f.seek(off);
        int len = f.readInt();
        int comp = f.readUnsignedByte();
        InputStream raw;
        if ((comp & 128) != 0) raw = Files.newInputStream(dir.resolve("c." + cx + "." + cz + ".mcc"));
        else { byte[] b = new byte[len - 1]; f.readFully(b); raw = new ByteArrayInputStream(b); }
        InputStream in = switch (comp & 127) { case 1 -> new GZIPInputStream(raw); case 2 -> new InflaterInputStream(raw); case 3 -> raw;
            default -> throw new IOException("compression " + comp); };
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in))) {
            if (d.readByte() != 10) return 0;
            d.skipBytes(d.readUnsignedShort());
            while (true) {
                byte t = d.readByte();
                if (t == 0) return 0;
                String name = d.readUTF();
                if (t == 4 && name.equals("InhabitedTime")) return d.readLong();
                skip(d, t);
            }
        }
    }

    static void skip(DataInputStream d, byte t) throws IOException {
        switch (t) {
            case 1 -> d.skipBytes(1); case 2 -> d.skipBytes(2); case 3, 5 -> d.skipBytes(4); case 4, 6 -> d.skipBytes(8);
            case 7 -> d.skipBytes(d.readInt()); case 8 -> d.skipBytes(d.readUnsignedShort());
            case 9 -> { byte et = d.readByte(); int n = d.readInt(); for (int i = 0; i < n; i++) skip(d, et); }
            case 10 -> { while (true) { byte et = d.readByte(); if (et == 0) break; d.skipBytes(d.readUnsignedShort()); skip(d, et); } }
            case 11 -> d.skipBytes(d.readInt() * 4); case 12 -> d.skipBytes(d.readInt() * 8);
            default -> throw new IOException("bad tag " + t);
        }
    }

    /** removes the marked chunks from one region file (and their oversized .mcc files) */
    static void wipeIn(Path r, boolean[] wipe, int rx, int rz) throws IOException {
        if (!Files.exists(r)) return;
        try (RandomAccessFile f = new RandomAccessFile(r.toFile(), "rw")) {
            if (f.length() < 8192) return;
            for (int i = 0; i < 1024; i++) {
                if (!wipe[i]) continue;
                f.seek(i * 4L); f.writeInt(0);
                f.seek(4096 + i * 4L); f.writeInt(0);
                Files.deleteIfExists(r.getParent().resolve("c." + (rx * 32 + (i & 31)) + "." + (rz * 32 + (i >> 5)) + ".mcc"));
            }
        }
    }

    static void preview(Path out, Map<Long, Integer> v, int minX, int minZ, int maxX, int maxZ) {
        try {
            if (v.isEmpty()) return;
            int wd = maxX - minX + 1, ht = maxZ - minZ + 1, s = Math.max(1, (int) Math.ceil(Math.max(wd, ht) / 1600.0));
            int up = Math.max(1, 800 / Math.max(wd, ht));
            BufferedImage img = new BufferedImage((wd + s - 1) / s * up, (ht + s - 1) / s * up, BufferedImage.TYPE_INT_RGB);
            int[] col = {0x3FBF5F, 0x4F8FFF, 0xFF5FFF, 0xFFD23F};
            for (var e : v.entrySet()) {
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                int c = e.getValue() < 0 ? 0x555555 : col[e.getValue()];
                int px = (cx - minX) / s, pz = (cz - minZ) / s;
                int old = img.getRGB(px * up, pz * up) & 0xffffff;
                if (old == 0 || old == 0x555555) for (int i = 0; i < up; i++) for (int j = 0; j < up; j++) img.setRGB(px * up + i, pz * up + j, c);
            }
            javax.imageio.ImageIO.write(img, "png", out.toFile());
        } catch (Throwable t) {
            System.out.println("(couldn't draw the preview map: " + t + ")");
        }
    }

    // ---- small gzip NBT file reader (level.dat, saved data)
    static Map<String, Object> nbtFile(Path p) throws IOException {
        if (!Files.exists(p)) return null;
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(p))))) {
            if (d.readByte() != 10) return null;
            d.skipBytes(d.readUnsignedShort());
            return (Map<String, Object>) read(d, (byte) 10);
        }
    }

    static Object read(DataInputStream d, byte t) throws IOException {
        switch (t) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: { byte[] b = new byte[d.readInt()]; d.readFully(b); return b; }
            case 8: return d.readUTF();
            case 9: { byte et = d.readByte(); int n = d.readInt(); List<Object> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(read(d, et)); return l; }
            case 10: { Map<String, Object> m = new LinkedHashMap<>(); while (true) { byte et = d.readByte(); if (et == 0) return m; String k = d.readUTF(); m.put(k, read(d, et)); } }
            case 11: { int n = d.readInt(); int[] x = new int[n]; for (int i = 0; i < n; i++) x[i] = d.readInt(); return x; }
            case 12: { int n = d.readInt(); long[] x = new long[n]; for (int i = 0; i < n; i++) x[i] = d.readLong(); return x; }
            default: throw new IOException("bad tag " + t);
        }
    }
}
