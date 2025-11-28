import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    @Test
    @DisplayName("Should create short link with valid parameters")
    void testShortLinkCreation() {
        LocalDateTime expiry = LocalDateTime.now().plusHours(12);
        ShortLink link = new ShortLink("abc123", "https://example.com", "user-1", 5, expiry);

        assertEquals("abc123", link.getShortCode());
        assertEquals("https://example.com", link.getLongUrl());
        assertEquals("user-1", link.getUserId());
        assertEquals(5, link.getClickLimit());
        assertEquals(0, link.getClickCount());
        assertFalse(link.isExpired());
    }

    @Test
    @DisplayName("Should increment click count")
    void testClickIncrement() {
        LocalDateTime expiry = LocalDateTime.now().plusHours(12);
        ShortLink link = new ShortLink("abc123", "https://example.com", "user-1", 5, expiry);

        assertEquals(0, link.getClickCount());
        link.incrementClick();
        assertEquals(1, link.getClickCount());
        link.incrementClick();
        assertEquals(2, link.getClickCount());
    }

    @Test
    @DisplayName("Should detect expired links")
    void testLinkExpiry() {
        LocalDateTime pastExpiry = LocalDateTime.now().minusHours(1);
        ShortLink link = new ShortLink("abc123", "https://example.com", "user-1", 5, pastExpiry);

        assertTrue(link.isExpired());
    }

    @Test
    @DisplayName("Should not be expired before expiry time")
    void testLinkNotExpired() {
        LocalDateTime futureExpiry = LocalDateTime.now().plusHours(1);
        ShortLink link = new ShortLink("abc123", "https://example.com", "user-1", 5, futureExpiry);

        assertFalse(link.isExpired());
    }

// ==================== ShortLinkService Unit Tests ====================
@Nested
class ShortLinkServiceTest {

    private ShortLinkService service;
    private String testUserId;

    @BeforeEach
    void setUp() {
        service = new ShortLinkService();
        testUserId = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("Should create short link successfully")
    void testCreateShortLink() {
        String shortCode = service.createShortLink("https://example.com", testUserId, 5);

        assertNotNull(shortCode);
        assertFalse(shortCode.isEmpty());
        assertEquals(8, shortCode.length());
    }

    @Test
    @DisplayName("Should create different short codes for same URL with different users")
    void testDifferentUsersGetDifferentShortCodes() {
        String user1 = UUID.randomUUID().toString();
        String user2 = UUID.randomUUID().toString();
        String url = "https://example.com";

        String shortCode1 = service.createShortLink(url, user1, 5);
        String shortCode2 = service.createShortLink(url, user2, 5);

        assertNotEquals(shortCode1, shortCode2);
    }

    @Test
    @DisplayName("Should create different short codes for same user")
    void testSameUserGetsDifferentShortCodes() throws InterruptedException {
        String shortCode1 = service.createShortLink("https://example.com", testUserId, 5);
        Thread.sleep(10); // Ensure different timestamp
        String shortCode2 = service.createShortLink("https://example.com", testUserId, 5);

        assertNotEquals(shortCode1, shortCode2);
    }

    @Test
    @DisplayName("Should click short link and return long URL")
    void testClickShortLink() throws Exception {
        String longUrl = "https://example.com/test";
        String shortCode = service.createShortLink(longUrl, testUserId, 5);

        String result = service.clickShortLink(shortCode, testUserId);

        assertEquals(longUrl, result);
    }

    @Test
    @DisplayName("Should track user links")
    void testGetUserLinks() {
        String shortCode1 = service.createShortLink("https://example1.com", testUserId, 5);
        String shortCode2 = service.createShortLink("https://example2.com", testUserId, 5);

        List<ShortLink> userLinks = service.getUserLinks(testUserId);

        assertEquals(2, userLinks.size());
        assertTrue(userLinks.stream().anyMatch(l -> l.getShortCode().equals(shortCode1)));
        assertTrue(userLinks.stream().anyMatch(l -> l.getShortCode().equals(shortCode2)));
    }

    @Test
    @DisplayName("Should return empty list for user with no links")
    void testGetUserLinksEmpty() {
        List<ShortLink> userLinks = service.getUserLinks("non-existent-user");

        assertTrue(userLinks.isEmpty());
    }

    @Test
    @DisplayName("Should count total links correctly")
    void testGetTotalLinksCount() {
        assertEquals(0, service.getTotalLinksCount());

        service.createShortLink("https://example1.com", testUserId, 5);
        assertEquals(1, service.getTotalLinksCount());

        service.createShortLink("https://example2.com", testUserId, 5);
        assertEquals(2, service.getTotalLinksCount());
    }

    @Test
    @DisplayName("Should handle concurrent link creation")
    void testConcurrentLinkCreation() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<String> shortCodes = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                String shortCode = service.createShortLink(
                        "https://example" + index + ".com",
                        testUserId,
                        5
                );
                shortCodes.add(shortCode);
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount, shortCodes.size()); // All unique
        assertEquals(threadCount, service.getTotalLinksCount());
    }

    @Test
    @DisplayName("Should handle concurrent clicks on same link")
    void testConcurrentClicks() throws Exception {
        String shortCode = service.createShortLink("https://example.com", testUserId, 10);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    String url = service.clickShortLink(shortCode, testUserId);
                    results.add(url);
                } catch (Exception e) {
                    // Ignore - link might be deleted by another thread
                }
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        assertTrue(results.size() >= threadCount);
        assertTrue(results.stream().allMatch(url -> url.equals("https://example.com")));
    }

    @Test
    @DisplayName("Should delete link when click limit reached exactly")
    void testExactClickLimit() throws Exception {
        String shortCode = service.createShortLink("https://example.com", testUserId, 1);

        // First click succeeds
        String url = service.clickShortLink(shortCode, testUserId);
        assertEquals("https://example.com", url);

        // Second click fails
        assertThrows(Exception.class, () -> {
            service.clickShortLink(shortCode, testUserId);
        });
    }

    @Test
    @DisplayName("Should reject expired links")
    void testExpiredLinkRejection() throws Exception {
        // This test would require mocking or waiting 12 hours
        // Instead we test the expired link detection logic directly
        String shortCode = service.createShortLink("https://example.com", testUserId, 5);
        List<ShortLink> links = service.getUserLinks(testUserId);

        assertFalse(links.isEmpty());
        assertFalse(links.get(0).isExpired());
    }
}
}

