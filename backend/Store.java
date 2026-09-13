import java.util.*;
import java.util.concurrent.*;

public class Store {
    final Map<String, User> users = new ConcurrentHashMap<>();
    final Map<String, Round> rounds = new ConcurrentHashMap<>();
    final Random random = new Random();
    private static final String[] REWARD_POOL = {
        "puzzle_piece_1", "puzzle_piece_2", "puzzle_piece_3", "puzzle_piece_4"
    };

    User getOrCreateDemoUser(Config cfg) {
        return users.computeIfAbsent("demo", id -> new User(id, "Demo Player", cfg.demoStartingBalance));
    }

    /** Places a bet, deducts balance, precomputes crash point + booster position server-side. */
       Round placeBet(User user, Theme theme, double betAmount, int boosterTier, Config cfg, Long seed) {
        if (betAmount <= 0) throw new IllegalArgumentException("Invalid bet amount");
        if (user.bonusBalance < betAmount) throw new IllegalStateException("INSUFFICIENT_BALANCE");

        // Dev mode (Section 2.3): if a seed is provided, use a deterministic RNG so the same
        // round (booster position + crash point) can be reproduced across sessions.
        Random rnd = (seed != null) ? new Random(seed) : random;

        int totalLevels = theme == Theme.RED ? cfg.levelsRed : cfg.levelsGreen;
        double boosterValue = 1.0;
        int boosterLevelIndex = 0;
        if (boosterTier == 2) boosterValue = cfg.boosterTier2Value;
        else if (boosterTier == 3) boosterValue = cfg.boosterTier3Value;
        else if (boosterTier == 4) boosterValue = cfg.boosterTier4Value;

        if (boosterTier >= 2) {
            // Booster sits on a random level, never the first or last level.
            int low = 2, high = Math.max(low, totalLevels - 1);
            boosterLevelIndex = low + rnd.nextInt(Math.max(1, high - low + 1));
        }

        double crashMultiplier = GameMath.computeCrashMultiplier(
            cfg.houseEdge, cfg.minCrashMultiplier, cfg.maxMultiplier, rnd);

        user.bonusBalance -= betAmount;

        String id = UUID.randomUUID().toString();
        String serverSeed = UUID.randomUUID().toString() + "-" + System.nanoTime();
        Round round = new Round(id, user.id, theme, totalLevels, betAmount,
            boosterTier, boosterValue, boosterLevelIndex, crashMultiplier, cfg, serverSeed);
        rounds.put(id, round);
        return round;
    }

    /** Computes live state of a round. Mutates+finalizes the round if the crash point has been reached. */
    RoundSnapshot snapshot(Round round) {
        double elapsed = round.elapsedSeconds();
        double base = GameMath.baseMultiplierAt(elapsed, round.growthRate);
        boolean justCrashedNow = false;

        if (round.state == RoundState.IN_PROGRESS && base >= round.crashMultiplier) {
            finalizeCrash(round);
            justCrashedNow = true;
        }
        if (round.state == RoundState.CASHED_OUT && !round.crashRevealed && base >= round.crashMultiplier) {
            round.crashRevealed = true;
        }

        double displayBase = Math.min(base, round.crashMultiplier);
        boolean boosterTimeReached = round.boosterLevelIndex > 0
            && elapsed >= GameMath.timeForMultiplier(
                GameMath.levelThreshold(round.boosterLevelIndex, round.levelStep), round.growthRate);
        boolean boosterActive = boosterTimeReached && round.state == RoundState.IN_PROGRESS;

        double displayMultiplier = boosterActive ? GameMath.round2(displayBase * round.boosterValue) : GameMath.round2(displayBase);
        int levelsCrossed = GameMath.levelsCrossed(displayBase, round.totalLevels, round.levelStep);

        boolean canCashout = round.state == RoundState.IN_PROGRESS && levelsCrossed >= 1;

        return new RoundSnapshot(round, displayMultiplier, levelsCrossed, boosterTimeReached, canCashout, justCrashedNow);
    }

    /** Attempts a cashout at "now". Returns the finalized snapshot, or throws if too late / not allowed. */
    synchronized RoundSnapshot cashout(Round round, User user) {
        if (round.state != RoundState.IN_PROGRESS) {
            throw new IllegalStateException("ROUND_ALREADY_FINISHED");
        }
        double elapsed = round.elapsedSeconds();
        double base = GameMath.baseMultiplierAt(elapsed, round.growthRate);

        if (base >= round.crashMultiplier) {
            finalizeCrash(round);
            throw new IllegalStateException("TOO_LATE_ALREADY_CRASHED");
        }
        int levelsCrossed = GameMath.levelsCrossed(base, round.totalLevels, round.levelStep);
        if (levelsCrossed < 1) {
            throw new IllegalStateException("MUST_PASS_LEVEL_1");
        }
        boolean boosterActive = round.boosterLevelIndex > 0
            && elapsed >= GameMath.timeForMultiplier(
                GameMath.levelThreshold(round.boosterLevelIndex, round.levelStep), round.growthRate);

        double finalMultiplier = boosterActive ? GameMath.round2(base * round.boosterValue) : GameMath.round2(base);
        double payout = GameMath.round2(round.betAmount * finalMultiplier);

        long points = (long) round.pointsPerLine * levelsCrossed + round.pointsCashoutBonus
            + (boosterActive ? round.pointsBoosterBonus : 0);

        round.cashoutMultiplier = finalMultiplier;
        round.payout = payout;
        round.pointsEarned = points;
        round.reward = REWARD_POOL[random.nextInt(REWARD_POOL.length)];
        round.state = RoundState.CASHED_OUT;

        user.bonusBalance = GameMath.round2(user.bonusBalance + payout);
        user.points += points;
        user.puzzlePieces.merge(round.reward, 1, Integer::sum);
        user.history.add(0, round);

        return snapshot(round);
    }

    private void finalizeCrash(Round round) {
        if (round.state != RoundState.IN_PROGRESS) return;
        int levelsCrossed = GameMath.levelsCrossed(round.crashMultiplier, round.totalLevels, round.levelStep);
        boolean boosterReached = round.boosterLevelIndex > 0
            && round.crashMultiplier >= GameMath.levelThreshold(round.boosterLevelIndex, round.levelStep);

        long points = (long) round.pointsPerLine * levelsCrossed + (boosterReached ? round.pointsBoosterBonus : 0);
        round.pointsEarned = points;
        round.payout = 0;
        round.reward = REWARD_POOL[random.nextInt(REWARD_POOL.length)];
        round.state = RoundState.CRASHED;

        User user = users.get(round.userId);
        if (user != null) {
            user.points += points;
            user.puzzlePieces.merge(round.reward, 1, Integer::sum);
            user.history.add(0, round);
        }
    }

    static class RoundSnapshot {
        final Round round;
        final double displayMultiplier;
        final int levelsCrossed;
        final boolean boosterActive;
        final boolean canCashout;
        final boolean justCrashedNow;

        RoundSnapshot(Round round, double displayMultiplier, int levelsCrossed,
                      boolean boosterActive, boolean canCashout, boolean justCrashedNow) {
            this.round = round;
            this.displayMultiplier = displayMultiplier;
            this.levelsCrossed = levelsCrossed;
            this.boosterActive = boosterActive;
            this.canCashout = canCashout;
            this.justCrashedNow = justCrashedNow;
        }
    }
}
