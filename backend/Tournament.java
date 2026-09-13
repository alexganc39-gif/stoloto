import java.util.*;

/**
 * Simulated player pool backing the leaderboard (Section 1.6) and the
 * tournament table (Section 1.7). Every "other player" here is fictional --
 * there are no real accounts, no real money, and nothing here is persisted
 * beyond process lifetime. Points tick upward slowly and deterministically
 * from elapsed server time, so the ranking keeps moving without needing a
 * background thread and stays consistent across requests within one run.
 */
public class Tournament {
    static final long SERVER_START = System.currentTimeMillis();
    // Matches the "25 ДНЕЙ" tournament-length badge shown in the product mock.
    static final long DURATION_MILLIS = 25L * 24 * 60 * 60 * 1000;

    private static final class SimPlayer {
        final String name;
        final double basePoints;
        final double ratePerSecond;
        SimPlayer(String name, double basePoints, double ratePerSecond) {
            this.name = name;
            this.basePoints = basePoints;
            this.ratePerSecond = ratePerSecond;
        }
    }

    private static final SimPlayer[] POOL = buildPool();

    private static SimPlayer[] buildPool() {
        String[] names = {
            "Алексей", "Мария", "Дмитрий", "Ольга", "Сергей", "Анна", "Иван", "Екатерина",
            "Павел", "Наталья", "Виктор", "Юлия", "Артём", "Светлана", "Максим", "Ксения",
            "Роман", "Татьяна", "Игорь", "Елена"
        };
        // Fixed seed: identities & starting points are stable across restarts,
        // only the elapsed-time component makes points climb.
        Random seedRnd = new Random(42);
        SimPlayer[] pool = new SimPlayer[names.length];
        for (int i = 0; i < names.length; i++) {
            double base = 200 + seedRnd.nextInt(2500);
            double rate = 0.015 + seedRnd.nextDouble() * 0.09; // slow, varied climb
            pool[i] = new SimPlayer(names[i], base, rate);
        }
        return pool;
    }

    public static final class Entry {
        public final String name;
        public final long points;
        public final boolean isMe;
        Entry(String name, long points, boolean isMe) {
            this.name = name;
            this.points = points;
            this.isMe = isMe;
        }
    }

    /** Full ranking (simulated pool + the given real user), sorted by points desc. */
    public static List<Entry> ranking(User user) {
        double elapsedSec = (System.currentTimeMillis() - SERVER_START) / 1000.0;
        List<Entry> list = new ArrayList<>();
        for (SimPlayer p : POOL) {
            long pts = Math.round(p.basePoints + p.ratePerSecond * elapsedSec);
            list.add(new Entry(p.name, pts, false));
        }
        if (user != null) {
            String label = (user.name == null || user.name.isEmpty()) ? "Вы" : user.name;
            list.add(new Entry(label, user.points, true));
        }
        list.sort((a, b) -> Long.compare(b.points, a.points));
        return list;
    }

    public static double hoursLeft() {
        long remaining = DURATION_MILLIS - (System.currentTimeMillis() - SERVER_START);
        return Math.max(0, remaining / (1000.0 * 60 * 60));
    }

    /** De-personalizes a name for the tournament table: keeps everything but first 3 chars, replaced with ***. */
    public static String mask(String name) {
        if (name == null || name.length() <= 3) return "***";
        return "***" + name.substring(3);
    }
}
