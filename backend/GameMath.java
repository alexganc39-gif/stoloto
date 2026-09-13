import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

public class GameMath {

    /** Standard provably-fair-style crash distribution. */
    public static double computeCrashMultiplier(double houseEdge, double min, double max, Random rnd) {
        double r = rnd.nextDouble(); // [0,1)
        if (r >= 0.999999) r = 0.999999; // avoid division blow-up
        double raw = (1.0 - houseEdge) / (1.0 - r);
        double rounded = Math.floor(raw * 100.0) / 100.0;
        return Math.max(min, Math.min(max, rounded));
    }

    /** Multiplier threshold required to have "crossed" level i (1-indexed). */
    public static double levelThreshold(int levelIndex, double levelStep) {
        return round2(Math.pow(1.0 + levelStep, levelIndex));
    }

    /** Base (pre-booster) multiplier at elapsed seconds t. */
    public static double baseMultiplierAt(double elapsedSeconds, double growthRate) {
        return Math.exp(growthRate * elapsedSeconds);
    }

    /** Inverse of baseMultiplierAt: seconds needed to reach a given multiplier. */
    public static double timeForMultiplier(double multiplier, double growthRate) {
        return Math.log(multiplier) / growthRate;
    }

    public static int levelsCrossed(double displayBaseMultiplier, int totalLevels, double levelStep) {
        int count = 0;
        for (int i = 1; i <= totalLevels; i++) {
            if (displayBaseMultiplier + 1e-9 >= levelThreshold(i, levelStep)) count++;
            else break;
        }
        return count;
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** SHA-256 hex digest, used for the provably-fair commit/reveal demo. */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
