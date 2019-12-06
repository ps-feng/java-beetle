package com.xing.beetle.redis;

import com.xing.beetle.dedup.spi.KeyValueStore;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Integration test for RedisDedupStore with a Redis container. */
class RedisDedupStoreTest {

  private static String redisServer = "";

  static {
    GenericContainer redis = startRedisContainer();
    redisServer = getRedisAddress(redis);
  }

  @NotNull
  private static String getRedisAddress(GenericContainer redisContainer) {
    return String.join(
        ":",
        new String[] {
          redisContainer.getContainerIpAddress(), redisContainer.getFirstMappedPort() + ""
        });
  }

  @NotNull
  private static GenericContainer startRedisContainer() {
    GenericContainer localRedis = new GenericContainer("redis:3.0.2").withExposedPorts(6379);
    localRedis.start();
    return localRedis;
  }

  @Test
  void testBasicOperations() {
    BeetleRedisProperties properties = new BeetleRedisProperties();
    properties.setRedisConfigurationMasterRetries(1);
    properties.setRedisServer(redisServer);
    RedisDedupStore store = new RedisDedupStore(properties);
    assertEquals("0", store.putIfAbsent("key", new KeyValueStore.Value("0")).getAsString());
    assertEquals(1, store.increase("key"));
    assertEquals("1", store.get("key").get().getAsString());

    store.delete("key");
    assertFalse(store.get("key").isPresent());
  }

  @Test
  void testMultiKeyDeletion() {
    BeetleRedisProperties properties = new BeetleRedisProperties();
    properties.setRedisConfigurationMasterRetries(1);
    properties.setRedisServer(redisServer);
    RedisDedupStore store = new RedisDedupStore(properties);
    store.put("key3", new KeyValueStore.Value("3"));
    store.put("key4", new KeyValueStore.Value("4"));
    assertTrue(store.get("key3").isPresent());
    assertTrue(store.get("key4").isPresent());
    store.delete("key3", "key4");
    assertFalse(store.get("key3").isPresent());
    assertFalse(store.get("key4").isPresent());
  }

  @Test
  void testPutIfAbsentWithTTL() throws InterruptedException {
    BeetleRedisProperties properties = new BeetleRedisProperties();
    properties.setRedisConfigurationMasterRetries(1);
    properties.setRedisServer(redisServer);
    RedisDedupStore store = new RedisDedupStore(properties);
    store.putIfAbsentTtl("keyTTL", new KeyValueStore.Value("ttl"), 1);
    assertTrue(store.get("keyTTL").isPresent());
    Thread.sleep(2000);
    assertFalse(store.get("keyTTL").isPresent());
  }

  @Test
  void testPutIfAbsent() {
    BeetleRedisProperties properties = new BeetleRedisProperties();
    properties.setRedisConfigurationMasterRetries(1);
    properties.setRedisServer(redisServer);
    RedisDedupStore store = new RedisDedupStore(properties);
    KeyValueStore.Value value = store.putIfAbsent("key", new KeyValueStore.Value("value"));
    assertEquals("value", value.getAsString());
    assertTrue(store.get("key").isPresent());
    KeyValueStore.Value existingValue =
        store.putIfAbsent("key", new KeyValueStore.Value("newValue"));
    assertEquals("value", existingValue.getAsString());
    store.delete("key");
  }

  @Test
  void testRetries() {

    GenericContainer localRedis = startRedisContainer();
    String localRedisServer = getRedisAddress(localRedis);
    BeetleRedisProperties properties = new BeetleRedisProperties();
    properties.setRedisServer(localRedisServer);
    properties.setRedisFailoverTimeout(20);
    properties.setRedisConfigurationMasterRetries(3);
    properties.setRedisConfigurationMasterRetryInterval(1);
    RedisDedupStore store = new RedisDedupStore(properties);
    assertEquals(1, store.increase("key"));
    localRedis.stop();

    long start = System.currentTimeMillis();

    DeduplicationException deduplicationException =
        Assertions.assertThrows(
            DeduplicationException.class,
            () -> {
              store.get("key");
            });
    assertEquals(deduplicationException.getMessage(), "Deduplication store request failed");
    long end = System.currentTimeMillis();
    // 3 retries with 1 sec interval
    assertTrue(end - start > 3000);
    localRedis.stop();
  }

  @Test
  void testTimeout() {

    GenericContainer localRedis = startRedisContainer();
    String localRedisServer = getRedisAddress(localRedis);
    BeetleRedisProperties properties = new BeetleRedisProperties();
    properties.setRedisServer(localRedisServer);
    properties.setRedisFailoverTimeout(3);
    properties.setRedisConfigurationMasterRetries(6);
    properties.setRedisConfigurationMasterRetryInterval(1);
    RedisDedupStore store = new RedisDedupStore(properties);
    assertEquals(1, store.increase("key"));
    localRedis.stop();

    long start = System.currentTimeMillis();

    DeduplicationException deduplicationException =
        Assertions.assertThrows(
            DeduplicationException.class,
            () -> {
              store.get("key");
            });
    assertEquals(deduplicationException.getMessage(), "Deduplication store request timed out");
    long end = System.currentTimeMillis();
    // timeout of 3 seconds should apply before 6 retries of 1 second each
    assertTrue(end - start > 3000 && end - start < 3500);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "system/"})
  void testServerAddressFromFile(String system, @TempDir Path tempDir) throws IOException {
    GenericContainer localRedis = startRedisContainer();
    String localRedisServer = getRedisAddress(localRedis);
    Path redisServerConfigFile = tempDir.resolve("redisServerConfig.txt");

    List<String> lines = Arrays.asList(system + localRedisServer);
    Files.write(redisServerConfigFile, lines);
    BeetleRedisProperties properties = new BeetleRedisProperties();
    properties.setSystemName("system");
    properties.setRedisServer(redisServerConfigFile.toString());

    properties.setRedisFailoverTimeout(1);
    properties.setRedisConfigurationMasterRetries(1);
    RedisDedupStore store = new RedisDedupStore(properties);
    assertEquals(1, store.increase("key"));
    localRedis.stop();
  }

  @Test
  void testServerAddressFromNonExistingFile() {
    BeetleRedisProperties properties = new BeetleRedisProperties();
    properties.setSystemName("system");
    properties.setRedisServer("redisServerConfig.txt");

    DeduplicationException deduplicationException =
        Assertions.assertThrows(
            DeduplicationException.class, () -> new RedisDedupStore(properties));
    assertEquals(deduplicationException.getMessage(), "Invalid redis address");
  }

  @Test
  void testServerAddressRedisNotRunning() {
    BeetleRedisProperties properties = new BeetleRedisProperties();
    properties.setSystemName("system");
    properties.setRedisServer("localhost:6399");

    DeduplicationException deduplicationException =
        Assertions.assertThrows(
            DeduplicationException.class, () -> new RedisDedupStore(properties));
    assertEquals(
        deduplicationException.getMessage(),
        "Cannot connect to redis at given address: localhost:6399");
  }
}
