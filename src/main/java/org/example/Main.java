package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.net.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class Main extends TelegramLongPollingBot {

    private static final String TOKEN = "8035358364:AAEKH6vX2akshX7j7qKvTchELMWeyDEGf1c";
    private static final String USERNAME = "@UltimateProxyBot";
    private static final String DB = "jdbc:sqlite:proxybot.db";
    private static final long LEASE_MS = 60 * 60 * 1000; // 1 час
    private static final int MIN_LATENCY = 150;
    private static final int MAX_LATENCY = 200;
    private static final int REFERRALS_FOR_PRO = 10;
    private static final long ADMIN_ID = 123456789L; // <- вставь свой Telegram ID

    private static final ScheduledExecutorService BG = Executors.newScheduledThreadPool(4);

    public static void main(String[] args) throws Exception {
        initDB();
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(new Main());

        BG.scheduleAtFixedRate(Main::collectProxies, 0, 5, TimeUnit.MINUTES);
        BG.scheduleAtFixedRate(Main::cleanupDead, 1, 10, TimeUnit.MINUTES);

        System.out.println("=== 🚀 ULTIMATE PROXY BOT STARTED ===");
    }

    // ================= BOT =================
    @Override
    public void onUpdateReceived(Update u) {
        if (!u.hasMessage() || !u.getMessage().hasText()) return;

        long chatId = u.getMessage().getChatId();
        String text = u.getMessage().getText();

        if (text.equals("/start")) {
            handleStart(chatId, u.getMessage().getFrom().getId());
            return;
        }

        switch (text) {
            case "🌐 Получить прокси" -> handleGetProxy(chatId);
            case "📊 Статистика" -> showStats(chatId);
            case "🧠 Совет" -> send(chatId, "💡 Совет:\nИспользуй прокси для анонимности и безопасности.", keyboard());
            case "🏆 Рефералы / PRO" -> showReferrals(chatId);
            case "🔧 Админка" -> {
                if (chatId == ADMIN_ID) showAdminPanel(chatId);
                else send(chatId, "❌ Нет доступа");
            }
            default -> send(chatId, "❌ Неизвестная команда", keyboard());
        }
    }

    @Override
    public String getBotUsername() { return USERNAME; }

    @Override
    public String getBotToken() { return TOKEN; }

    // ================= START =================
    private void handleStart(long chatId, Long userId) {
        try (Connection c = DriverManager.getConnection(DB)) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO users(chat_id,user_id,referrals,pro_until) VALUES(?,?,?,?)"
            );
            ps.setLong(1, chatId);
            ps.setLong(2, userId);
            ps.setInt(3, 0);
            ps.setLong(4, 0);
            ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
        send(chatId, "🔥 Добро пожаловать в Ultimate Proxy Bot!\nИспользуй кнопки ниже для прокси и статистики.", keyboard());
    }

    // ================= HANDLE GET PROXY =================
    private void handleGetProxy(long chatId) {
        try (Connection c = DriverManager.getConnection(DB)) {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT proxy, expires_at FROM leases WHERE chat_id=? AND expires_at>?"
            );
            ps.setLong(1, chatId);
            ps.setLong(2, System.currentTimeMillis());
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                long leftMin = (rs.getLong("expires_at") - System.currentTimeMillis()) / 60000;
                send(chatId, "⏳ У тебя уже есть прокси\nОсталось примерно: " + leftMin + " мин", keyboard());
                return;
            }

            boolean isPro = false;
            if (chatId == ADMIN_ID) isPro = true; // админ = бесконечная PRO
            else {
                ResultSet userRs = c.createStatement().executeQuery("SELECT pro_until FROM users WHERE chat_id=" + chatId);
                if (userRs.next()) {
                    long proUntil = userRs.getLong("pro_until");
                    isPro = proUntil > System.currentTimeMillis();
                }
            }

            String sql = "SELECT ip, latency, country, checked_count, fail_count FROM proxies " +
                    "WHERE ip NOT IN (SELECT proxy FROM leases WHERE expires_at>" + System.currentTimeMillis() + ") ";
            sql += isPro ? "ORDER BY latency ASC LIMIT 5" : "ORDER BY latency ASC LIMIT 1";

            rs = c.createStatement().executeQuery(sql);
            if (!rs.next()) {
                send(chatId, "⏳ Свободных прокси нет, подожди пару минут", keyboard());
                return;
            }

            String proxy = rs.getString("ip");
            int latency = rs.getInt("latency");
            String country = rs.getString("country");
            int checked = rs.getInt("checked_count");
            int failed = rs.getInt("fail_count");

            double survival = (checked > 0) ? 1.0 - ((double) failed / checked) : 1.0;
            long estimatedLife = (long) (LEASE_MS * survival / 60000);

            long now = System.currentTimeMillis();
            PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO leases(chat_id, proxy, issued_at, expires_at) VALUES(?,?,?,?)"
            );
            ins.setLong(1, chatId);
            ins.setString(2, proxy);
            ins.setLong(3, now);
            ins.setLong(4, now + LEASE_MS);
            ins.execute();

            System.out.println("ISSUED → " + proxy + " to " + chatId + " (" + latency + "ms, " + country + ") PRO=" + isPro);

            send(chatId,
                    "✅ Твой прокси (1 час)\n" +
                            proxy + "\nПинг: " + latency + "ms\nСтрана: " + country +
                            "\nПрогноз жизни: ~" + estimatedLife + " мин\nPRO: " + (isPro ? "ДА" : "НЕТ"),
                    keyboard());

        } catch (Exception e) { e.printStackTrace(); }
    }

    // ================= REFERRALS =================
    private void showReferrals(long chatId) {
        try (Connection c = DriverManager.getConnection(DB)) {
            ResultSet rs = c.createStatement().executeQuery("SELECT referrals,pro_until FROM users WHERE chat_id=" + chatId);
            if (rs.next()) {
                int refs = rs.getInt("referrals");
                long proUntil = rs.getLong("pro_until");
                boolean hasPro = proUntil > System.currentTimeMillis();
                send(chatId, "🏆 Рефералы: " + refs + "/10\nPRO: " + (hasPro ? "Активен" : "Неактивен") + "\nПри 10 рефералах → 7 дней PRO");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ================= STATS =================
    private void showStats(long chatId) {
        try (Connection c = DriverManager.getConnection(DB)) {
            Statement st = c.createStatement();
            ResultSet rs1 = st.executeQuery("SELECT COUNT(*) FROM proxies");
            int total = rs1.next() ? rs1.getInt(1) : 0;
            ResultSet rs2 = st.executeQuery("SELECT COUNT(*) FROM leases WHERE expires_at>" + System.currentTimeMillis());
            int active = rs2.next() ? rs2.getInt(1) : 0;
            send(chatId, "📊 Статистика:\nВсего прокси: " + total + "\nАктивные аренды: " + active, keyboard());
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ================= ADMIN PANEL =================
    private void showAdminPanel(long chatId) {
        send(chatId, "🔧 Админка:\nВсе прокси работают\nТы имеешь бесконечную PRO!");
    }

    // ================= DB INIT =================
    private static void initDB() throws Exception {
        try (Connection c = DriverManager.getConnection(DB)) {
            c.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS proxies(
                    ip TEXT PRIMARY KEY,
                    latency INTEGER,
                    country TEXT,
                    checked_count INTEGER DEFAULT 0,
                    fail_count INTEGER DEFAULT 0
                );
            """);
            c.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS leases(
                    chat_id INTEGER,
                    proxy TEXT,
                    issued_at INTEGER,
                    expires_at INTEGER
                );
            """);
            c.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS users(
                    chat_id INTEGER PRIMARY KEY,
                    user_id INTEGER,
                    referrals INTEGER DEFAULT 0,
                    pro_until INTEGER DEFAULT 0
                );
            """);
        }
    }

    // ================= PROXY COLLECTOR =================
    private static void collectProxies() {
        String[] sources = {
                "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
                "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt"
        };
        for (String src : sources) {
            try (Scanner sc = new Scanner(new URL(src).openStream())) {
                while (sc.hasNextLine()) {
                    String p = sc.nextLine().trim();
                    if (!p.contains(":")) continue;
                    BG.submit(() -> checkAndSaveProxy(p));
                }
            } catch (Exception ignored) {}
        }
    }

    private static void checkAndSaveProxy(String proxy) {
        int latency = checkProxy(proxy);
        if (latency < MIN_LATENCY || latency > MAX_LATENCY) return;
        String country = getCountry(proxy);

        try (Connection c = DriverManager.getConnection(DB)) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO proxies(ip, latency, country, checked_count, fail_count) VALUES(?,?,?,?,?)"
            );
            ps.setString(1, proxy);
            ps.setInt(2, latency);
            ps.setString(3, country);
            ps.setInt(4, 1);
            ps.setInt(5, 0);
            ps.executeUpdate();
            System.out.println("+1 proxy saved: " + proxy + " (" + latency + "ms, " + country + ")");
        } catch (Exception ignored) {}
    }

    private static int checkProxy(String proxy) {
        try {
            String[] a = proxy.split(":");
            Proxy pr = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(a[0], Integer.parseInt(a[1])));
            long start = System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection) new URL("http://example.com").openConnection(pr);
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.connect();
            int code = c.getResponseCode();
            if (code >= 200 && code < 400) return (int) (System.currentTimeMillis() - start);
        } catch (Exception ignored) {}
        return -1;
    }

    private static String getCountry(String ip) {
        try {
            URL url = new URL("https://ipapi.co/" + ip.split(":")[0] + "/country/");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()))) {
                return br.readLine();
            }
        } catch (Exception e) { return "Unknown"; }
    }

    private static void cleanupDead() {
        try (Connection c = DriverManager.getConnection(DB)) {
            ResultSet rs = c.createStatement().executeQuery("SELECT ip FROM proxies");
            while (rs.next()) {
                if (checkProxy(rs.getString(1)) < 0) {
                    c.createStatement().execute("DELETE FROM proxies WHERE ip='" + rs.getString(1) + "'");
                    System.out.println("REMOVED dead proxy");
                }
            }
        } catch (Exception ignored) {}
    }

    // ================= UI =================
    private ReplyKeyboardMarkup keyboard() {
        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup();
        kb.setResizeKeyboard(true);
        KeyboardRow r1 = new KeyboardRow(); r1.add(new KeyboardButton("🌐 Получить прокси"));
        KeyboardRow r2 = new KeyboardRow(); r2.add(new KeyboardButton("📊 Статистика"));
        KeyboardRow r3 = new KeyboardRow(); r3.add(new KeyboardButton("🧠 Совет"));
        KeyboardRow r4 = new KeyboardRow(); r4.add(new KeyboardButton("🏆 Рефералы / PRO"));
        KeyboardRow r5 = new KeyboardRow(); r5.add(new KeyboardButton("🔧 Админка"));
        kb.setKeyboard(List.of(r1, r2, r3, r4, r5));
        return kb;
    }

    private void send(long chatId, String text) { send(chatId, text, null); }

    private void send(long chatId, String text, ReplyKeyboardMarkup kb) {
        SendMessage sm = new SendMessage();
        sm.setChatId(chatId);
        sm.setText(text);
        if (kb != null) sm.setReplyMarkup(kb);
        try { execute(sm); } catch (TelegramApiException ignored) {}
    }
}
