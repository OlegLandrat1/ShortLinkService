import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.*;

public class Main {
    private final ShortLinkService service;
    private final Scanner scanner;
    private String currentUserId;

    public Main() {
        this.service = new ShortLinkService();
        this.scanner = new Scanner(System.in);
        this.currentUserId = loadOrCreateUserId();
    }

    public static void main(String[] args) {
        Main sls = new Main();
        sls.run();
    }

    public void run() {
        System.out.println("=== Сервис коротких ссылок ===");
        System.out.println(" Ваш User ID: " + currentUserId);
        System.out.println();

        while (true) {
            showMenu();
            String choise = scanner.nextLine().trim();

            switch (choise) {
                case "1":
                    createShortLink();
                    break;
                case "2":
                    openShortLink();
                    break;
                case "3":
                    listMyLinks();
                    break;
                case "4":
                    showStats();
                    break;
                case "5":
                    System.out.println("Программа завершена!");
                    service.shutdown();
                    return;
                default:
                    System.out.println("Не корректный выбор. Выберите пункт меню");
            }
            System.out.println();
        }

    }

    private void showMenu() {
        System.out.println("1. Создать короткую ссылку");
        System.out.println("2. Открыть короткую ссылку");
        System.out.println("3. Список ссылок");
        System.out.println("4. Показать статистику");
        System.out.println("5. Выход");
        System.out.println("Выберите пункт меню: ");
    }

    private void createShortLink() {
        System.out.println("Введите URL: ");
        String longUrl = scanner.nextLine().trim();

        System.out.println("Лимит переходов: ");
        int clickLimit = Integer.parseInt(scanner.nextLine().trim());

        try {
            String shortCode = service.createShortLink(longUrl, currentUserId, clickLimit);
            System.out.println("Короткая ссылка создана: " + shortCode);
        } catch (Exception e) {
            System.out.println("Ошибка: " + e.getMessage());
        }
    }

    private void openShortLink() {
        System.out.println("Введите код короткой ссылки: ");
        String shortCode = scanner.nextLine().trim();

        try {
            String longUrl = service.clickShortLink(shortCode, currentUserId);
            System.out.println("Переход по ссылке: " + longUrl);
            openInBrowser(longUrl);
        } catch (Exception e) {
            System.out.println("Ошибка: " + e.getMessage());
        }
    }

    private void listMyLinks() {
        List<ShortLink> links = service.getUserLinks(currentUserId);
        if (links.isEmpty()) {
            System.out.println("Ссылок не найдено.");
            return;
        }

        System.out.println("\n=== Ваши ссылки ===");
        for (ShortLink link : links) {
            System.out.println("Код короткой ссылки: " + link.getShortCode());
            System.out.println(" URL: " + link.getLongUrl());
            System.out.println(" Кол-во переходов: " + link.getClickCount() + "/" + link.getClickLimit());
            System.out.println(" Время жизни ссылки: " + link.getExpiryTime());
        }
    }

    private void showStats() {
        System.out.println("Всего ссылок создано: " + service.getTotalLinksCount());
        System.out.println("Из них ваших ссылок: " + service.getUserLinks(currentUserId).size());
    }

    private void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                System.out.println("Невозможно запустить браузер. Откройте браузер и пререйдите по ссылке: " + url);
            }
        } catch (Exception e) {
            System.out.println("Ошибка запуска браузера: " + e.getMessage());
        }
    }

    private String loadOrCreateUserId() {
        Path userIdFile = Paths.get("user_id.txt");
        try {
            if (Files.exists(userIdFile)) {
                return Files.readString(userIdFile).trim();
            } else {
                String newUserId = UUID.randomUUID().toString();
                Files.writeString(userIdFile, newUserId);
                return  newUserId;
            }
        } catch (IOException e) {
            return UUID.randomUUID().toString();
        }
    }
}

// ==================== Core Service ====================

class ShortLinkService {
    private static final int LIFETIME = 12; // Hours
    private final Map<String, ShortLink> linkStore;
    private final Map<String, List<String>> userLinks;
    private final ScheduledExecutorService scheduler;
    private final NotificationService notificationService;

    public ShortLinkService() {
        this.linkStore = new ConcurrentHashMap<>();
        this.userLinks = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.notificationService = new NotificationService();
    }

    public String createShortLink(String longUrl, String userId, int clickLimit) {
        String shortCode = generateUniqueShortCode(userId);
        LocalDateTime expiryTime = LocalDateTime.now().plusHours(LIFETIME);

        ShortLink link = new ShortLink(shortCode, longUrl, userId, clickLimit, expiryTime);
        linkStore.put(shortCode, link);

        userLinks.computeIfAbsent(userId, k -> new ArrayList<>()).add(shortCode);

        // Schedule expiry task
        scheduleExpiry(shortCode, userId);

        return shortCode;
    }

    public String clickShortLink(String shortCode, String userId) throws Exception {
        ShortLink link = linkStore.get(shortCode);

        if (link == null) {
            throw new Exception("Короткая ссылка не найдена");
        }

        if (link.isExpired()) {
            deleteLink(shortCode, link.getUserId());
            throw new Exception("Время работы ссылки истекло.");
        }

        synchronized (link) {
            if (link.getClickCount() >= link.getClickLimit()) {
                notificationService.notifyClickLimitReached(link.getUserId(), shortCode);
                deleteLink(shortCode, link.getUserId());
                throw new Exception("Кол-во переходов по ссылке достигло лимита. Ссылка удалена.");
            }

            link.incrementClick();

            if (link.getClickCount() >= link.getClickLimit()) {
                notificationService.notifyClickLimitReached(link.getUserId(), shortCode);
                deleteLink(shortCode, link.getUserId());
            }
        }

        return  link.getLongUrl();
    }

    public List<ShortLink> getUserLinks(String userId) {
        List<String> codes = userLinks.getOrDefault(userId, new ArrayList<>());
        List<ShortLink> links = new ArrayList<>();
        for (String code : codes) {
            ShortLink link = linkStore.get(code);
            if (link != null) {
                links.add(link);
            }
        }
        return links;
    }

    public int getTotalLinksCount() {
        return linkStore.size();
    }

    private void deleteLink(String shortCode, String userId) {
        linkStore.remove(shortCode);
        List<String> codes = userLinks.get(userId);
        if (codes != null) {
            codes.remove(shortCode);
        }
    }

    private void scheduleExpiry(String shortCode, String userId) {
        scheduler.schedule(() -> {
            ShortLink link = linkStore.get(shortCode);
            if (link != null && link.isExpired()) {
                notificationService.notifyExpiry(userId, shortCode);
                deleteLink(shortCode, userId);
            }
        }, LIFETIME, TimeUnit.HOURS);
    }

    private String generateUniqueShortCode(String userId) {
        String shortCode;
        do {
            String base = userId.substring(0, 8) + System.currentTimeMillis();
            shortCode = generateShortCode(base);
        }
        while (linkStore.containsKey(shortCode));
        return  shortCode;
    }

    private String generateShortCode(String input) {
        int hash = input.hashCode();
        StringBuilder sb = new StringBuilder();
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

        for (int i = 0; i < 8; i++) {
            hash = Math.abs(hash);
            sb.append(chars.charAt(hash % chars.length()));
            hash = hash / chars.length() + (int) (Math.random() * 1000);
        }

        return sb.toString();
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}

// ==================== Domain Model ====================

class ShortLink {
    private final String shortCode;
    private final String longUrl;
    private final String userId;
    private final int clickLimit;
    private int clickCount;
    private  final LocalDateTime creationTime;
    private final LocalDateTime expiryTime;

    public String getShortCode() {
        return shortCode;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public String getUserId() {
        return userId;
    }

    public int getClickLimit() {
        return clickLimit;
    }

    public int getClickCount() {
        return clickCount;
    }

    public LocalDateTime getCreationTime() {
        return creationTime;
    }

    public LocalDateTime getExpiryTime() {
        return expiryTime;
    }

    public ShortLink(String shortCode, String longUrl, String userId, int clickLimit, LocalDateTime expiryTime) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.userId = userId;
        this.clickLimit = clickLimit;
        this.clickCount = 0;
        this.creationTime = LocalDateTime.now();
        this.expiryTime = expiryTime;
    }

    public synchronized void incrementClick() {
        this.clickCount++;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiryTime);
    }
}

// ==================== Notification Service ====================

class NotificationService {

    public void notifyClickLimitReached(String userId, String shortCode) {
        System.out.println("\n[ОПОВЕЩЕНИЕ] Достигнут лимит переходов по ссылке: " + shortCode);
        System.out.println("Ссылка удалена.\n");
    }

    public void notifyExpiry(String userId, String shortCode) {
        System.out.println("\n[ОПОВЕЩЕНИЕ] Ссылка устарела: " + shortCode);
        System.out.println("Время работы ссылки истекло. Ссылка удалена.\n");
    }
}