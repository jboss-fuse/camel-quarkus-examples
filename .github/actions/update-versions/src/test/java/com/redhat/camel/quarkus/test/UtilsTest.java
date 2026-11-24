package com.redhat.camel.quarkus.test;

import java.util.Map;
import java.util.Map.Entry;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class UtilsTest {

    @Test
    void getLatestCqPluginVersion() {
        Assertions.assertThat(Utils.getLatestCqPluginVersion()).matches("[\\d]+\\.[\\d]+\\.[\\d]+");
    }

    @Test
    void collectVersions() {
        Map<String, String> versions = Utils.collectVersions(UpdateVersionsTest.quarkusRegistryBaseUrl, UpdateVersionsTest.minimalCEQVersion);
        Assertions.assertThat(versions).hasSizeGreaterThan(0);

        for (Entry<String, String> en : versions.entrySet()) {
            Assertions.assertThat(en.getKey()).matches("[\\d]+\\.[\\d]+\\.x-product");
            Assertions.assertThat(en.getValue()).matches(".*[\\.\\-]redhat-[0-9]{5}");
        }
    }

}
