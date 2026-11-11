package com.payflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookTargetValidatorTest {

  private final WebhookTargetValidator strict =
      new WebhookTargetValidator(TestProperties.build(false, false, 8));
  private final WebhookTargetValidator relaxed =
      new WebhookTargetValidator(TestProperties.build(true, true, 8));

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://127.0.0.1/hook",
        "https://localhost/hook",
        "https://10.1.2.3/hook",
        "https://192.168.0.5/hook",
        "https://172.16.0.1/hook",
        "https://169.254.169.254/latest/meta-data",
        "https://[::1]/hook",
        "https://[fd00::1]/hook",
        "https://0.0.0.0/hook",
        "https://100.64.0.1/hook"
      })
  void internalAddressesAreRefused(String url) {
    assertThatThrownBy(() -> strict.validate(url))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-public");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://203.0.113.10/hook",
        "ftp://203.0.113.10/hook",
        "file:///etc/passwd",
        "//203.0.113.10/hook",
        "https://user:pw@203.0.113.10/hook",
        "not a url",
        "https:///nohost"
      })
  void nonHttpsOrOddUrlsAreRefused(String url) {
    assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPublicHttpsAddressIsAccepted() {
    assertThat(strict.validate("https://203.0.113.10:8443/hook").getPort()).isEqualTo(8443);
  }

  @Test
  void unresolvableHostsAreRefused() {
    assertThatThrownBy(() -> strict.validate("https://definitely-not-a-real-host.invalid/hook"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resolved");
  }

  @Test
  void theRelaxedModeAllowsLocalHttpTargetsForDevelopment() {
    assertThat(relaxed.validate("http://localhost:9999/hook").getHost()).isEqualTo("localhost");
    assertThatThrownBy(() -> relaxed.validate("ftp://localhost/hook"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
