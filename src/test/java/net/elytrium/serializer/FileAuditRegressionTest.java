package net.elytrium.serializer;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.elytrium.serializer.annotations.RegisterPlaceholders;
import net.elytrium.serializer.language.object.YamlSerializable;
import net.elytrium.serializer.placeholders.DefaultPlaceholderReplacer;
import net.elytrium.serializer.placeholders.Placeholders;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAuditRegressionTest {

  @Test
  void rapidBackupsPreserveEveryRevision(@TempDir Path directory) throws Exception {
    Settings settings = new Settings();
    Path path = directory.resolve("config.yml");
    Set<String> expected = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      String value = "first: revision-" + i + "\n";
      expected.add(value);
      Files.writeString(path, value);
      settings.backup(path);
    }
    Set<String> actual = new HashSet<>();
    try (var files = Files.list(directory)) {
      for (Path backup : files.filter(file -> !file.equals(path)).toList()) {
        actual.add(Files.readString(backup));
      }
    }
    Assertions.assertEquals(expected, actual);
  }

  @Test
  void failedLoadDoesNotPartiallyChangeFields() {
    Settings settings = new Settings();
    Assertions.assertThrows(RuntimeException.class,
        () -> settings.load(new BufferedReader(new StringReader("first: changed\nsecond: \"unterminated\n"))));
    Assertions.assertEquals("first", settings.first);
    Assertions.assertEquals("second", settings.second);
  }

  @Test
  void failedLoadDoesNotPartiallyChangeNestedFields() {
    Settings settings = new Settings();
    Nested original = settings.nested;
    Assertions.assertThrows(RuntimeException.class,
        () -> settings.load(new BufferedReader(new StringReader("nested:\n  value: changed\nsecond: \"unterminated\n"))));
    Assertions.assertSame(original, settings.nested);
    Assertions.assertEquals("original", settings.nested.value);
  }

  @Test
  void failedLoadPreservesStringStyle() throws Exception {
    Settings settings = new Settings();
    settings.load(new BufferedReader(new StringReader("first: 'first'\n")));
    var field = Settings.class.getDeclaredField("first");
    var style = settings.getStringStyle(field);
    Assertions.assertNotNull(style);
    Assertions.assertThrows(RuntimeException.class,
        () -> settings.load(new BufferedReader(new StringReader("first: |-\n  changed\nsecond: \"unterminated\n"))));
    Assertions.assertEquals(style, settings.getStringStyle(field));
    Assertions.assertEquals("first", settings.first);
  }

  @Test
  void failedLoadDoesNotReplaceExistingPlaceholderMetadata() {
    AnnotatedSettings settings = new AnnotatedSettings();
    String original = settings.first;
    Placeholders.addPlaceholders(original, new DefaultPlaceholderReplacer(), "old");
    try {
      Assertions.assertThrows(RuntimeException.class,
          () -> settings.load(new BufferedReader(new StringReader("second: \"unterminated\n"))));
      Assertions.assertEquals("yes {new}", Placeholders.replace(original, "yes"));
    } finally {
      Placeholders.removePlaceholders(original);
    }
  }

  @Test
  void reloadPreservesBlockScalarAndFollowingField(@TempDir Path directory) throws Exception {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, "first: |\n  one\n\n  two\nsecond: kept\n");
    Settings settings = new Settings();
    Assertions.assertEquals(LoadResult.SUCCESS, settings.reload(path));
    Settings reloaded = new Settings();
    reloaded.load(path);
    Assertions.assertEquals("one\n\ntwo\n", reloaded.first);
    Assertions.assertEquals("kept", reloaded.second);
  }

  private static class Settings extends YamlSerializable {

    private String first = "first";
    private String second = "second";
    private Nested nested = new Nested();
  }

  private static class Nested {

    private String value = "original";
  }

  private static class AnnotatedSettings extends YamlSerializable {

    @RegisterPlaceholders("new")
    private String first = new String("{old} {new}");
    private String second;
  }
}
