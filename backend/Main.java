import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

public class Main {
    static final Store store = new Store();

    // Fixed bet "puzzle fragments" shown on the bet-selection screen: amount + booster tier.
    static final double[] FRAGMENT_AMOUNTS = {50, 150, 300, 600};
    static final int[] FRAGMENT_BOOSTERS = {0, 2, 3, 4};

    // Upsell popup (Section 1.8): only offered when a win's payout reaches this amount.
    static final double MIN_WIN_AMOUNT = 50;

    public static void main(String[] args) throws IOException {
                String portEnv = System.getenv("PORT");
        int port = (portEnv != null)
            ? Integer.parseInt(portEnv)
            : (args.length > 0 ? Integer.parseInt(args[0]) : 8080);
        Config cfg = Config.load();
        store.getOrCreateDemoUser(cfg); // seed demo user with a non-zero balance

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/api/state", Main::handleState);
        server.createContext("/api/bet", Main::handleBet);
        server.createContext("/api/round", Main::handleRound);
        server.createContext("/api/cashout", Main::handleCashout);
        server.createContext("/api/history", Main::handleHistory);
        server.createContext("/api/config", Main::handleConfig);
        server.createContext("/api/leaderboard", Main::handleLeaderboard);
        server.createContext("/api/tournament", Main::handleTournament);
        server.createContext("/api/buyTickets", Main::handleBuyTickets);
        server.createContext("/api/health", Main::handleHealth);
        server.createContext("/", Main::handleStatic);

        server.start();
        System.out.println("Balloon (Vozdushny Shar) backend running on http://localhost:" + port);
        System.out.println("Demo user 'demo' seeded with balance " + cfg.demoStartingBalance);
    }

    // ---- /api/state : user + theme level counts + fragment options ----
    static void handleState(HttpExchange ex) throws IOException {
        Map<String, String> q = Json.parseQuery(ex.getRequestURI().getQuery());
        String userId = q.getOrDefault("userId", "demo");
        Config cfg = Config.load();
        User user = store.users.computeIfAbsent(userId, id -> new User(id, id, cfg.demoStartingBalance));

        List<String> fragments = new ArrayList<>();
        for (int i = 0; i < FRAGMENT_AMOUNTS.length; i++) {
            fragments.add(new Json()
                .put("amount", FRAGMENT_AMOUNTS[i])
                .put("boosterTier", FRAGMENT_BOOSTERS[i])
                .build());
        }
        Json rewards = new Json();
        for (Map.Entry<String, Integer> e : user.puzzlePieces.entrySet()) {
            rewards.put(e.getKey(), e.getValue());
        }

        String json = new Json()
            .put("userId", user.id)
            .put("balance", user.bonusBalance)
            .put("points", user.points)
            .put("levelsRed", cfg.levelsRed)
            .put("levelsGreen", cfg.levelsGreen)
            .putRaw("fragments", Json.array(fragments))
            .putRaw("rewards", rewards.build())
            .build();
        sendJson(ex, 200, json);
    }

    // ---- POST /api/bet : userId, theme(RED|GREEN), betAmount, boosterTier(0|2|3|4) ----
    static void handleBet(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("METHOD_NOT_ALLOWED")); return; }
        Map<String, String> form = Json.parseForm(ex.getRequestBody());
        String userId = form.getOrDefault("userId", "demo");
        Config cfg = Config.load();
        User user = store.users.computeIfAbsent(userId, id -> new User(id, id, cfg.demoStartingBalance));

        try {
                        Theme theme = Theme.valueOf(form.getOrDefault("theme", "GREEN").toUpperCase());
            double amount = Double.parseDouble(form.getOrDefault("betAmount", "0"));
            int boosterTier = Integer.parseInt(form.getOrDefault("boosterTier", "0"));

            // Dev mode (Section 2.3): optional fixed seed for reproducible rounds.
            Long seed = null;
            String seedStr = form.get("seed");
            if (seedStr != null && !seedStr.isEmpty()) {
                try { seed = Long.parseLong(seedStr.trim()); }
                catch (NumberFormatException ignored) { /* fall through to random seed */ }
            }

            Round round = store.placeBet(user, theme, amount, boosterTier, cfg, seed);
            String json = new Json()
                .put("roundId", round.id)
                .put("theme", round.theme.name())
                .put("totalLevels", round.totalLevels)
                .put("betAmount", round.betAmount)
                .put("boosterTier", round.boosterTier)
                .put("commitHash", round.commitHash)
                .put("balance", user.bonusBalance)
                .build();
            sendJson(ex, 200, json);
        } catch (IllegalStateException e) {
            sendJson(ex, 400, err(e.getMessage()));
        } catch (Exception e) {
            sendJson(ex, 400, err("BAD_REQUEST: " + e.getMessage()));
        }
    }

    // ---- GET /api/round?id=... : live snapshot (polled every ~150ms by the client) ----
    static void handleRound(HttpExchange ex) throws IOException {
        Map<String, String> q = Json.parseQuery(ex.getRequestURI().getQuery());
        Round round = store.rounds.get(q.get("id"));
        if (round == null) { sendJson(ex, 404, err("ROUND_NOT_FOUND")); return; }

        Store.RoundSnapshot snap = store.snapshot(round);
        sendJson(ex, 200, snapshotJson(snap));
    }

    // ---- POST /api/cashout : id ----
    static void handleCashout(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("METHOD_NOT_ALLOWED")); return; }
        Map<String, String> form = Json.parseForm(ex.getRequestBody());
        Round round = store.rounds.get(form.get("id"));
        if (round == null) { sendJson(ex, 404, err("ROUND_NOT_FOUND")); return; }
        User user = store.users.get(round.userId);
        try {
            Store.RoundSnapshot snap = store.cashout(round, user);
            sendJson(ex, 200, snapshotJson(snap));
        } catch (IllegalStateException e) {
            sendJson(ex, 409, err(e.getMessage()));
        }
    }

    static String snapshotJson(Store.RoundSnapshot snap) {
        Round r = snap.round;
        Json j = new Json()
            .put("roundId", r.id)
            .put("state", r.state.name())
            .put("theme", r.theme.name())
            .put("totalLevels", r.totalLevels)
            .put("displayMultiplier", snap.displayMultiplier)
            .put("levelsCrossed", snap.levelsCrossed)
            .put("boosterActive", snap.boosterActive)
            .put("boosterLevelIndex", r.boosterLevelIndex)
            .put("boosterTier", r.boosterTier)
            .put("canCashout", snap.canCashout)
            .put("justCrashedNow", snap.justCrashedNow)
            .put("crashRevealed", r.crashRevealed || r.state == RoundState.CRASHED)
            .put("betAmount", r.betAmount);
        if (r.state != RoundState.IN_PROGRESS || r.crashRevealed) {
            j.put("crashMultiplier", r.crashMultiplier)
             .put("crashMultiplierStr", r.crashMultiplierStr)
             .put("serverSeed", r.serverSeed)
             .put("commitHash", r.commitHash);
        }
        if (r.state == RoundState.CASHED_OUT) {
            j.put("cashoutMultiplier", r.cashoutMultiplier)
             .put("payout", r.payout)
             .put("pointsEarned", r.pointsEarned)
             .put("reward", r.reward);
            User winner = store.users.get(r.userId);
            boolean showUpsell = winner != null && r.payout >= MIN_WIN_AMOUNT && !winner.upsellShownThisSession;
            j.put("showUpsell", showUpsell);
            if (showUpsell) winner.upsellShownThisSession = true;
        }
        if (r.state == RoundState.CRASHED) {
            j.put("pointsEarned", r.pointsEarned)
             .put("reward", r.reward);
        }
        User u = store.users.get(r.userId);
        if (u != null) j.put("balance", u.bonusBalance).put("totalPoints", u.points);
        return j.build();
    }

    // ---- GET /api/history?userId=... ----
    static void handleHistory(HttpExchange ex) throws IOException {
        Map<String, String> q = Json.parseQuery(ex.getRequestURI().getQuery());
        User user = store.users.get(q.getOrDefault("userId", "demo"));
        List<String> items = new ArrayList<>();
        if (user != null) {
            int limit = 20;
            for (Round r : user.history) {
                if (items.size() >= limit) break;
                items.add(new Json()
                    .put("theme", r.theme.name())
                    .put("betAmount", r.betAmount)
                    .put("resultMultiplier", r.state == RoundState.CASHED_OUT ? r.cashoutMultiplier : r.crashMultiplier)
                    .put("state", r.state.name())
                    .put("payout", r.payout)
                    .put("pointsEarned", r.pointsEarned)
                    .build());
            }
        }
        sendJson(ex, 200, "{\"history\":" + Json.array(items) + "}");
    }

    // ---- GET/POST /api/config : view or hot-update the config.properties file ----
    static void handleConfig(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {
            Map<String, String> form = Json.parseForm(ex.getRequestBody());
            Properties current = new Properties();
            Path path = Paths.get("config.properties");
            if (Files.exists(path)) {
                try (Reader r = new FileReader(path.toFile())) { current.load(r); }
            }
            for (Map.Entry<String, String> e : form.entrySet()) {
                current.setProperty(e.getKey(), e.getValue());
            }
            try (Writer w = new FileWriter(path.toFile())) {
                current.store(w, "Updated via admin API at " + new Date());
            }
            sendJson(ex, 200, "{\"status\":\"ok\",\"note\":\"applies to NEW rounds only\"}");
            return;
        }
        Config cfg = Config.load();
        String json = new Json()
            .put("growthRate", cfg.growthRate)
            .put("houseEdge", cfg.houseEdge)
            .put("minCrashMultiplier", cfg.minCrashMultiplier)
            .put("maxMultiplier", cfg.maxMultiplier)
            .put("levelStep", cfg.levelStep)
            .put("levelsRed", cfg.levelsRed)
            .put("levelsGreen", cfg.levelsGreen)
            .put("pointsPerLine", cfg.pointsPerLine)
            .put("pointsBoosterBonus", cfg.pointsBoosterBonus)
            .put("pointsCashoutBonus", cfg.pointsCashoutBonus)
            .put("boosterTier2Value", cfg.boosterTier2Value)
            .put("boosterTier3Value", cfg.boosterTier3Value)
            .put("boosterTier4Value", cfg.boosterTier4Value)
            .put("demoStartingBalance", cfg.demoStartingBalance)
            .build();
        sendJson(ex, 200, json);
    }

    // ---- GET /api/leaderboard?userId=... : top-12 simulated players + current user (Section 1.6) ----
    static void handleLeaderboard(HttpExchange ex) throws IOException {
        Map<String, String> q = Json.parseQuery(ex.getRequestURI().getQuery());
        String userId = q.getOrDefault("userId", "demo");
        User user = store.users.get(userId);

        List<Tournament.Entry> ranking = Tournament.ranking(user);
        List<String> items = new ArrayList<>();
                for (int i = 0; i < Math.min(12, ranking.size()); i++) {
            Tournament.Entry e = ranking.get(i);
            String displayName = e.isMe ? e.name : Tournament.mask(e.name);
            items.add(new Json()
                .put("rank", i + 1)
                .put("name", displayName)
                .put("points", e.points)
                .put("isMe", e.isMe)
                .build());
        }
        sendJson(ex, 200, "{\"leaderboard\":" + Json.array(items) + "}");
    }

    // ---- GET /api/tournament?userId=... : full ranked list + myRank + hoursLeft (Section 1.7) ----
    static void handleTournament(HttpExchange ex) throws IOException {
        Map<String, String> q = Json.parseQuery(ex.getRequestURI().getQuery());
        String userId = q.getOrDefault("userId", "demo");
        User user = store.users.get(userId);

        List<Tournament.Entry> ranking = Tournament.ranking(user);
        List<String> items = new ArrayList<>();
        int myRank = -1;
        for (int i = 0; i < ranking.size(); i++) {
            Tournament.Entry e = ranking.get(i);
            if (e.isMe) myRank = i + 1;
            String displayName = e.isMe ? e.name : Tournament.mask(e.name);
            items.add(new Json()
                .put("rank", i + 1)
                .put("name", displayName)
                .put("points", e.points)
                .put("isMe", e.isMe)
                .build());
        }
        String json = new Json()
            .put("myRank", myRank)
            .put("totalPlayers", ranking.size())
            .put("hoursLeft", GameMath.round2(Tournament.hoursLeft()))
            .putRaw("ranking", Json.array(items))
            .build();
        sendJson(ex, 200, json);
    }

    // ---- POST /api/buyTickets : userId, tickets, price : upsell purchase (Section 1.8) ----
    // FIX: per the brief, purchase must spend BONUS POINTS (bonusBalance), not game points (points).
    static void handleBuyTickets(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("METHOD_NOT_ALLOWED")); return; }
        Map<String, String> form = Json.parseForm(ex.getRequestBody());
        String userId = form.getOrDefault("userId", "demo");
        User user = store.users.get(userId);
        if (user == null) { sendJson(ex, 404, err("USER_NOT_FOUND")); return; }

        try {
            int tickets = Integer.parseInt(form.getOrDefault("tickets", "0"));
            long price = Long.parseLong(form.getOrDefault("price", "0"));
            if (tickets <= 0 || price <= 0) { sendJson(ex, 400, err("BAD_REQUEST")); return; }
            if (user.bonusBalance < price) { sendJson(ex, 409, err("INSUFFICIENT_BALANCE")); return; }

            user.bonusBalance = GameMath.round2(user.bonusBalance - price);
            user.ticketsOwned += tickets;

            String json = new Json()
                .put("ticketsOwned", user.ticketsOwned)
                .put("balance", user.bonusBalance)
                .put("points", user.points)
                .build();
            sendJson(ex, 200, json);
        } catch (NumberFormatException e) {
            sendJson(ex, 400, err("BAD_REQUEST"));
        }
    }
    // ---- GET /api/health : lightweight liveness probe (Section 2.1) ----
    static void handleHealth(HttpExchange ex) throws IOException {
        sendJson(ex, 200, "{\"status\":\"ok\",\"timestamp\":" + System.currentTimeMillis() + "}");
    }


    // ---- static file serving for the frontend ----
    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = Paths.get("../frontend" + path).normalize();
        if (!file.startsWith(Paths.get("../frontend").normalize()) || !Files.exists(file) || Files.isDirectory(file)) {
            sendText(ex, 404, "Not found");
            return;
        }
        String contentType = path.endsWith(".js") ? "application/javascript"
            : path.endsWith(".css") ? "text/css"
            : path.endsWith(".html") ? "text/html; charset=utf-8"
            : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String err(String message) {
        return new Json().put("error", message).build();
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = json.getBytes("UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes("UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
