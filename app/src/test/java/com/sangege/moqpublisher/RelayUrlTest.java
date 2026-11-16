package com.sangege.moqpublisher;

import org.junit.Assert;
import org.junit.Test;

public class RelayUrlTest {

    @Test
    public void appendsJwtToUrlWithoutQuery() {
        Assert.assertEquals(
                "https://relay.example/live?jwt=abc.def",
                RelayUrl.INSTANCE.withJwt("https://relay.example/live", "abc.def")
        );
    }

    @Test
    public void preservesExistingQueryAndEncodesToken() {
        Assert.assertEquals(
                "https://relay.example/live?region=tw&jwt=a%2Bb%3D",
                RelayUrl.INSTANCE.withJwt("https://relay.example/live?region=tw", "a+b=")
        );
    }

    @Test
    public void doesNotDuplicateExistingJwtParameter() {
        String url = "https://relay.example/live?JWT=existing&region=tw";
        Assert.assertEquals(url, RelayUrl.INSTANCE.withJwt(url, "new-token"));
    }

    @Test
    public void stripsCommonTokenPrefixes() {
        Assert.assertEquals(
                "https://relay.example/live?jwt=abc",
                RelayUrl.INSTANCE.withJwt("https://relay.example/live", "jwt=abc")
        );
    }

    @Test
    public void masksJwtWhenDisplayed() {
        String masked = RelayUrl.INSTANCE.maskJwt(
                "https://relay.example/live?jwt=secret&region=tw"
        );
        Assert.assertEquals("https://relay.example/live?jwt=****&region=tw", masked);
        Assert.assertFalse(masked.contains("secret"));
    }
}
