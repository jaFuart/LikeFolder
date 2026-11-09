package com.likefolder.app;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class YandexApiCdnTest {
    private static final String HOST = "cdn.example.org";
    private static final String PATH = "/music/test-track.mp3";
    // Independently calculated from the synthetic path and salt with Python hashlib.md5.
    private static final String SIGNED_PREFIX =
            "https://cdn.example.org/get-mp3/803dcdbb83831a5cecfd09a5fde16261/";

    private static String xml(String host, String path, String ts) {
        return "<download-info><host>" + host + "</host><path>" + path
                + "</path><ts>" + ts + "</ts><s>test-salt</s></download-info>";
    }

    @Test public void hexadecimalTimestampProducesExpectedSignedUrl() throws Exception {
        assertEquals(SIGNED_PREFIX + "1a02f27aee4" + PATH,
                YandexApi.mp3UrlFromXml(xml(HOST, PATH, "1a02f27aee4")));
    }

    @Test public void digitOnlyTimestampRemainsSupported() throws Exception {
        assertEquals(SIGNED_PREFIX + "1668329810" + PATH,
                YandexApi.mp3UrlFromXml(xml(HOST, PATH, "1668329810")));
    }

    @Test public void timestampCaseAndLeadingZerosArePreserved() throws Exception {
        for (String ts : new String[]{"0001A02F27AEE4", "0001a02F27aEe4"}) {
            assertEquals(SIGNED_PREFIX + ts + PATH,
                    YandexApi.mp3UrlFromXml(xml(HOST, PATH, ts)));
        }
    }

    @Test public void invalidTimestampReportsFieldWithoutLeakingValue() {
        for (String ts : new String[]{"1a02/private", "1a02?key=private", "1a02#private",
                "1a02g", "0x1a02", "1a02 private"}) {
            IOException error = assertThrows(IOException.class,
                    () -> YandexApi.mp3UrlFromXml(xml(HOST, PATH, ts)));
            assertEquals("Неподдерживаемый ответ CDN: неверное поле ts", error.getMessage());
        }
    }

    @Test public void invalidHostReportsFieldWithoutLeakingValue() {
        IOException error = assertThrows(IOException.class,
                () -> YandexApi.mp3UrlFromXml(xml("cdn.example.org/private", PATH, "1a02f")));
        assertEquals("Неподдерживаемый ответ CDN: неверное поле host", error.getMessage());
    }

    @Test public void relativePathIsRejectedWithoutLeakingValue() {
        IOException error = assertThrows(IOException.class,
                () -> YandexApi.mp3UrlFromXml(xml(HOST, "music/private-track.mp3", "1a02f")));
        assertEquals("Неподдерживаемый ответ CDN: неверное поле path", error.getMessage());
    }

    @Test public void missingRequiredFieldsAreReported() {
        for (String field : new String[]{"host", "path", "ts", "s"}) {
            String incomplete = xml(HOST, PATH, "1a02f")
                    .replaceFirst("<" + field + ">[^<]*</" + field + ">", "");
            IOException error = assertThrows(IOException.class,
                    () -> YandexApi.mp3UrlFromXml(incomplete));
            assertEquals("Нет поля " + field + " в CDN-ответе", error.getMessage());
        }
    }
}
