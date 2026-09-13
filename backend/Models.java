import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

class User {
    final String id;
    String name;
    double bonusBalance;
    long points;
    int ticketsOwned = 0; // simulated lottery tickets bought via the upsell popup (Section 1.8)
    boolean upsellShownThisSession = false; // "at most once per session" flag (Section 1.8)
    Map<String, Integer> puzzlePieces = new HashMap<>(); // reward collection, e.g. "piece_1" -> count
    final CopyOnWriteArrayList<Round> history = new CopyOnWriteArrayList<>();

    User(String id, String name, double startingBalance) {
        this.id = id;
        this.name = name;
        this.bonusBalance = startingBalance;
    }
}

enum Theme { RED, GREEN }

enum RoundState { IN_PROGRESS, CASHED_OUT, CRASHED }

class Round {
    final String id;
    final String userId;
    final Theme theme;
    final int totalLevels;
    final double betAmount;
    final int boosterTier;          // 0 = no booster, else 2/3/4
    final double boosterValue;      // multiplier value for the chosen tier (1.0 if none)
    final int boosterLevelIndex;    // which level (1..totalLevels) the booster sits on, 0 if none
    final double crashMultiplier;   // server-precomputed, authoritative
    final double growthRate;
    final double levelStep;
    final int pointsPerLine;
    final int pointsBoosterBonus;
    final int pointsCashoutBonus;
    final long startTimeMillis;
    final String serverSeed;        // revealed only after round ends (provably-fair demo)
    final String crashMultiplierStr; // exact string used to build the hash (Java double->String), sent to the
                                      // client so its recompute uses the identical bytes -- JS's own number-to-
                                      // string formatting for a double like 50.0 can differ from Java's ("50" vs "50.0").
    final String commitHash;        // sha256(serverSeed + ":" + crashMultiplier), shown BEFORE round starts

    volatile RoundState state = RoundState.IN_PROGRESS;
    volatile double cashoutMultiplier = -1;
    volatile double payout = 0;
    volatile long pointsEarned = 0;
    volatile String reward = null;
    volatile boolean crashRevealed = false; // for display purposes after a cashout, once the balloon visually pops

    Round(String id, String userId, Theme theme, int totalLevels, double betAmount,
          int boosterTier, double boosterValue, int boosterLevelIndex,
          double crashMultiplier, Config cfg, String serverSeed) {
        this.id = id;
        this.userId = userId;
        this.theme = theme;
        this.totalLevels = totalLevels;
        this.betAmount = betAmount;
        this.boosterTier = boosterTier;
        this.boosterValue = boosterValue;
        this.boosterLevelIndex = boosterLevelIndex;
        this.crashMultiplier = crashMultiplier;
        this.growthRate = cfg.growthRate;
        this.levelStep = cfg.levelStep;
        this.pointsPerLine = cfg.pointsPerLine;
        this.pointsBoosterBonus = cfg.pointsBoosterBonus;
        this.pointsCashoutBonus = cfg.pointsCashoutBonus;
        this.startTimeMillis = System.currentTimeMillis();
        this.serverSeed = serverSeed;
        this.crashMultiplierStr = String.valueOf(crashMultiplier);
        this.commitHash = GameMath.sha256(serverSeed + ":" + this.crashMultiplierStr);
    }

    double elapsedSeconds() {
        return (System.currentTimeMillis() - startTimeMillis) / 1000.0;
    }
}
