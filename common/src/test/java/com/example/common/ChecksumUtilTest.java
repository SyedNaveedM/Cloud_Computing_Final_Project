package com.example.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChecksumUtilTest {

    @Test
    void sha256OfEmptyArrayMatchesKnownVector() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ChecksumUtil.sha256(new byte[0]));
    }

    @Test
    void sha256OfKnownStringMatchesKnownVector() {
        assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                ChecksumUtil.sha256("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sha256IsDeterministic() {
        byte[] data = "deterministic".getBytes(StandardCharsets.UTF_8);
        assertEquals(ChecksumUtil.sha256(data), ChecksumUtil.sha256(data));
    }
}
