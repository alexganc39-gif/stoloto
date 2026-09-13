import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * All tunable game-economy parameters live in config.properties.
 * Loaded fresh every time a NEW round is created, so an admin/expert can edit
 * the file, save it, start a new round, and immediately see the new values
 * applied -- no server restart, no code change (Scenario 5 in the brief).
 * Each in-progress Round keeps its own snapshot of these values so editing
 * the file mid-flight never changes the outcome of a round already running.
 */
public class Config {
    public double growthRate;          // multiplier(t) = e^(growthRate * t)
    public double houseEdge;           // crash-point distribution edge
    public double minCrashMultiplier;  // floor for crash point
    public double maxMultiplier;       // ceiling for crash point
    public double levelStep;           // level i threshold = (1+levelStep)^i
    public int levelsRed;              // number of levels, red theme
    public int levelsGreen;            // number of levels, green theme
    public int pointsPerLine;          // points per level crossed
    public int pointsBoosterBonus;     // extra points when booster triggers
    public int pointsCashoutBonus;     // extra points on successful cashout
    public double boosterTier2Value;
    public double boosterTier3Value;
    public double boosterTier4Value;
    public double demoStartingBalance;
    public int popupTimeoutSeconds;    // idle timeout on result screen

    private static final String PATH = "config.properties";

    public static synchronized Config load() {
        Properties p = new Properties();
        Path path = Paths.get(PATH);
        try {
            if (Files.exists(path)) {
                try (Reader r = new FileReader(path.toFile())) {
                    p.load(r);
                }
            } else {
                writeDefaults(path);
                try (Reader r = new FileReader(path.toFile())) {
                    p.load(r);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not read config.properties, using built-in defaults: " + e.getMessage());
        }
        Config c = new Config();
        c.growthRate = num(p, "growth.rate", 0.18);
        c.houseEdge = num(p, "house.edge", 0.03);
        c.minCrashMultiplier = num(p, "min.crash.multiplier", 1.00);
        c.maxMultiplier = num(p, "max.multiplier", 50.0);
        c.levelStep = num(p, "level.step", 0.15);
        c.levelsRed = (int) num(p, "levels.red", 12);
        c.levelsGreen = (int) num(p, "levels.green", 9);
        c.pointsPerLine = (int) num(p, "points.per.line", 10);
        c.pointsBoosterBonus = (int) num(p, "points.booster.bonus", 25);
        c.pointsCashoutBonus = (int) num(p, "points.cashout.bonus", 15);
        c.boosterTier2Value = num(p, "booster.tier2.value", 2.0);
        c.boosterTier3Value = num(p, "booster.tier3.value", 3.0);
        c.boosterTier4Value = num(p, "booster.tier4.value", 4.0);
        c.demoStartingBalance = num(p, "demo.starting.balance", 1000);
        c.popupTimeoutSeconds = (int) num(p, "popup.timeout.seconds", 10);
        return c;
    }

    private static double num(Properties p, String key, double def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void writeDefaults(Path path) throws IOException {
        String defaults = "# Balloon (Vozdushny Shar) game economy config\n"
            + "# Edit and save this file, then start a NEW round to see changes applied.\n"
            + "\n# Crash-point model: crash = clamp((1-house.edge)/(1-r), min, max), r = uniform(0,1)\n"
            + "house.edge=0.03\n"
            + "min.crash.multiplier=1.00\n"
            + "max.multiplier=50.0\n"
            + "\n# multiplier(t) = e^(growth.rate * t), t in seconds since round start\n"
            + "growth.rate=0.18\n"
            + "\n# Level i threshold = (1+level.step)^i, i=1..levels.<theme>\n"
            + "level.step=0.15\n"
            + "levels.red=12\n"
            + "levels.green=9\n"
            + "\n# Points\n"
            + "points.per.line=10\n"
            + "points.booster.bonus=25\n"
            + "points.cashout.bonus=15\n"
            + "\n# Booster multiplier values selected at bet time (x2/x3/x4 fragment)\n"
            + "booster.tier2.value=2.0\n"
            + "booster.tier3.value=3.0\n"
            + "booster.tier4.value=4.0\n"
            + "\n# Demo user starting / top-up balance\n"
            + "demo.starting.balance=1000\n"
            + "\n# Idle timeout on result screen before auto-redirect\n"
            + "popup.timeout.seconds=10\n";
        // FIX: write in UTF-8 so Russian comments don't turn into mojibake on Windows.
        Files.write(path, defaults.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}