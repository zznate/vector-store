package io.github.zznate.vectorstore.app.testprofile;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Enables both L2 tiers (off-heap arena + on-disk) with deliberately
 * tiny budgets so a modest workload spills past L1 and the off-heap
 * tier into the disk tier — sufficient to make {@code tier=l2_disk}
 * counters move during integration tests without producing minutes-long
 * suite runs.
 *
 * <p>L1: 1 block (64 KiB). L2 off-heap: 2 blocks (128 KiB). L2 disk:
 * 16 MiB (plenty of headroom for promotions). Disk path lives under
 * {@code target/l2-disk-it} so the surefire teardown cleans it up.
 */
public final class L2DiskCacheProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.ofEntries(
        Map.entry("vectorstore.cache.block.bytes", "65536"),
        Map.entry("vectorstore.cache.block.block-size", "65536"),
        Map.entry("vectorstore.cache.block.l2.enabled", "true"),
        Map.entry("vectorstore.cache.block.l2.bytes", "131072"),
        Map.entry("vectorstore.cache.block.l2-disk.enabled", "true"),
        Map.entry("vectorstore.cache.block.l2-disk.path", "target/l2-disk-it"),
        Map.entry("vectorstore.cache.block.l2-disk.bytes", "16777216"));
  }
}
