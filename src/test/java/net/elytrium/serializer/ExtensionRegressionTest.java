package net.elytrium.serializer;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.elytrium.serializer.annotations.RegisterPlaceholders;
import net.elytrium.serializer.annotations.Serializer;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.language.reader.YamlReader;
import net.elytrium.serializer.placeholders.DefaultPlaceholderReplacer;
import net.elytrium.serializer.placeholders.Placeholders;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ExtensionRegressionTest {

  @Test
  void eachListEntryUsesItsOwnRegistration() {
    String first = new String("{first}");
    String second = new String("{second}");
    try {
      Placeholders.addPlaceholders(first, new DefaultPlaceholderReplacer(), "first");
      Placeholders.addPlaceholders(second, new DefaultPlaceholderReplacer(), "second");
      Assertions.assertEquals(List.of("yes", "yes"), Placeholders.replace(List.of(first, second), "yes"));
    } finally {
      Placeholders.removePlaceholders(first);
      Placeholders.removePlaceholders(second);
    }
  }

  @Test
  void legacyHashCannotOverwriteAnObjectRegistration() {
    String value = new String("{first} {second}");
    int hash = System.identityHashCode(value);
    try {
      Placeholders.addPlaceholders(value, new DefaultPlaceholderReplacer(), "first");
      Placeholders.addPlaceholders(hash, new DefaultPlaceholderReplacer(), new String[]{"second"});
      Assertions.assertEquals("yes {second}", Placeholders.replace(value, "yes"));
    } finally {
      Placeholders.removePlaceholders(value);
      Placeholders.removePlaceholders(hash);
    }
  }

  @Test
  void fallbackPreservesWrappingFlag() {
    int key = 141234;
    try {
      Placeholders.setPlaceholders(key, new DefaultPlaceholderReplacer(), false, "name");
      Assertions.assertArrayEquals(new String[]{"name"}, Placeholders.getPlaceholders(key));
    } finally {
      Placeholders.removePlaceholders(key);
    }
  }

  @Test
  void replacementsDoNotMutateCallerArrays() {
    Object[] values = {1, 2};
    DefaultPlaceholderReplacer replacer = new DefaultPlaceholderReplacer();
    Assertions.assertEquals("1 2", replacer.replace("{a} {b}", new String[]{"{a}", "{b}"}, values));
    Assertions.assertArrayEquals(new Object[]{1, 2}, values);
    Integer[] integers = {1, 2};
    Assertions.assertEquals("1 2", replacer.replace("{a} {b}", new String[]{"{a}", "{b}"}, (Object[]) integers));
  }

  @Test
  void emptyPlaceholderIsRejectedBeforeReplacement() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new DefaultPlaceholderReplacer().replace("value", new String[]{""}, "replacement"));
  }

  @Test
  void unannotatedSharedFieldDoesNotRemoveRegistration() {
    SharedFields settings = new SharedFields();
    try {
      new YamlReader(new BufferedReader(new StringReader("{}\n"))).readSerializableObject(settings, SharedFields.class);
      Assertions.assertEquals("yes", Placeholders.replace(settings.first, "yes"));
    } finally {
      Placeholders.removePlaceholders(settings.first);
    }
  }

  @Test
  void inheritedReplacerCanBeRegistered() {
    ChildReplacer replacer = new ChildReplacer();
    SerializerConfig config = new SerializerConfig.Builder().registerReplacer(replacer).build();
    Assertions.assertSame(replacer, config.getRegisteredReplacer(String.class));
    String value = new String("{name}");
    try {
      Placeholders.addPlaceholders(value, replacer, "name");
      Assertions.assertEquals("yes", Placeholders.replace(value, "yes"));
    } finally {
      Placeholders.removePlaceholders(value);
    }
  }

  @Test
  void extensionLookupWalksTransitiveInterfaces() {
    BaseSerializer serializer = new BaseSerializer();
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(serializer).build();
    Assertions.assertSame(serializer, config.getRegisteredSerializer(Implementation.class));
  }

  @Test
  void transitiveInterfaceTakesPrecedenceOverObjectFallback() {
    BaseSerializer serializer = new BaseSerializer();
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new ObjectSerializer()).registerSerializer(serializer).build();
    Assertions.assertSame(serializer, config.getRegisteredSerializer(Implementation.class));
  }

  @Test
  void objectRemovalAlsoRemovesLegacyFallback() {
    String value = new String("{first}");
    Placeholders.addPlaceholders(System.identityHashCode(value), new DefaultPlaceholderReplacer(), new String[]{"first"});
    Assertions.assertTrue(Placeholders.hasPlaceholders(value));
    Placeholders.removePlaceholders(value);
    Assertions.assertFalse(Placeholders.hasPlaceholders(value));
  }

  @Test
  void equalButDistinctHoldersKeepSeparateSpecifications() {
    String first = new String("{first} {second}");
    String second = new String(first);
    try {
      Placeholders.addPlaceholders(first, new DefaultPlaceholderReplacer(), "first");
      Placeholders.addPlaceholders(second, new DefaultPlaceholderReplacer(), "second");
      Assertions.assertEquals("yes {second}", Placeholders.replace(first, "yes"));
      Assertions.assertEquals("{first} yes", Placeholders.replace(second, "yes"));
    } finally {
      Placeholders.removePlaceholders(first);
      Placeholders.removePlaceholders(second);
    }
  }

  @Test
  void annotationReusesRegisteredInstance() throws ReflectiveOperationException {
    CountingSerializer serializer = new CountingSerializer();
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(serializer).build();
    Serializer annotation = AnnotatedField.class.getDeclaredField("value").getAnnotation(Serializer.class);
    Assertions.assertSame(serializer, config.getAndCacheSerializer(annotation));
  }

  @Test
  void cacheCreatesOnlyOneInstanceForConcurrentReaders() throws Exception {
    SerializerConfig config = new SerializerConfig.Builder().build();
    Serializer annotation = AnnotatedField.class.getDeclaredField("value").getAnnotation(Serializer.class);
    CountingSerializer.CREATED.set(0);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> {
        ready.countDown();
        start.await();
        return config.getAndCacheSerializer(annotation);
      });
      var second = executor.submit(() -> {
        ready.countDown();
        start.await();
        return config.getAndCacheSerializer(annotation);
      });
      Assertions.assertTrue(ready.await(2, TimeUnit.SECONDS));
      start.countDown();
      Assertions.assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      Assertions.assertEquals(1, CountingSerializer.CREATED.get());
    } finally {
      start.countDown();
    }
  }

  @Test
  void builderChangesDoNotMutateBuiltConfigurations() {
    SerializerConfig.Builder builder = new SerializerConfig.Builder();
    SerializerConfig config = builder.build();
    builder.registerSerializer(new CountingSerializer());
    Assertions.assertNull(config.getRegisteredSerializer(String.class));
  }

  private interface Base {
  }

  private interface Child extends Base {
  }

  private static class Implementation implements Child {
  }

  private static class BaseSerializer extends ClassSerializer<Base, String> {
  }

  private static class ObjectSerializer extends ClassSerializer<Object, String> {
  }

  private static class ChildReplacer extends DefaultPlaceholderReplacer {
  }

  private static class SharedFields {

    @RegisterPlaceholders("name")
    private String first = new String("{name}");
    private String second = this.first;
  }

  private static class AnnotatedField {

    @Serializer(CountingSerializer.class)
    private String value;
  }

  private static class CountingSerializer extends ClassSerializer<String, String> {

    private static final AtomicInteger CREATED = new AtomicInteger();

    private CountingSerializer() {
      CountingSerializer.CREATED.incrementAndGet();
      try {
        Thread.sleep(40);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }
}
