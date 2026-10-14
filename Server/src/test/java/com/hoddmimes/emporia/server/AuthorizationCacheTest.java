package com.hoddmimes.emporia.server;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationCacheTest {

    private static Document auth(String pFirm, String pMarket) {
        return new Document("firm", pFirm).append("market", pMarket);
    }

    @Test
    void marketIdsPerFirm() {
        AuthorizationCache tCache = new AuthorizationCache();
        tCache.set(List.of(auth("FA", "1"), auth("FA", "3"), auth("FB", "2"), auth("FB", "x")));

        assertEquals(Set.of(1, 3), tCache.authorizedMarkets("FA", Set::of));
        assertEquals(Set.of(2), tCache.authorizedMarkets("FB", Set::of));        // "x" is ignored
        assertEquals(Set.of(), tCache.authorizedMarkets("FC", Set::of));
        assertEquals(Set.of(), tCache.authorizedMarkets(null, Set::of));
        assertTrue(tCache.isAuthorized("FA", 3, m -> true));
        assertFalse(tCache.isAuthorized("FA", 2, m -> true));
        assertFalse(tCache.isAuthorized(null, 1, m -> true));
    }

    @Test
    void allMarketsIsExpandedAtLookupTime() {
        AuthorizationCache tCache = new AuthorizationCache();
        tCache.set(List.of(auth("FA", "*"), auth("FA", "9")));

        assertEquals(Set.of(1, 2, 9), tCache.authorizedMarkets("FA", () -> Set.of(1, 2)));
        assertEquals(Set.of(1, 2, 5, 9), tCache.authorizedMarkets("FA", () -> Set.of(1, 2, 5)));   // a new market
        assertTrue(tCache.isAuthorized("FA", 5, m -> m == 5));
        assertFalse(tCache.isAuthorized("FA", 6, m -> m == 5));                // no such market
        assertTrue(tCache.isAuthorized("FA", 9, m -> false));                  // listed explicitly
    }

    @Test
    void setReplacesTheAuthorizations() {
        AuthorizationCache tCache = new AuthorizationCache();
        tCache.set(List.of(auth("FA", "1")));
        tCache.set(List.of(auth("FB", "1")));

        assertFalse(tCache.isAuthorized("FA", 1, m -> true));
        assertTrue(tCache.isAuthorized("FB", 1, m -> true));
    }
}
