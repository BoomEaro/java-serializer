package net.elytrium.serializer;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.elytrium.serializer.annotations.FallbackNodeNames;
import net.elytrium.serializer.annotations.Transient;
import net.elytrium.serializer.language.object.JsonSerializable;
import net.elytrium.serializer.language.object.YamlSerializable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class ReaderReauditRegressionTest {

  @Test
  void emptySequenceEntriesRemainSiblingsOnReload(@TempDir Path directory) throws Exception {
    for (String indent : List.of("", "  ")) {
      for (String empty : List.of("-", "- # empty")) {
        String input = "list:\n" + indent + empty + "\n" + indent + "- second\n" + indent + empty
            + "\n" + indent + "- fourth\n" + indent + empty + "\nnext: kept\n";
        Settings settings = reload(directory, input);
        List<Object> expected = Arrays.asList(null, "second", null, "fourth", null);
        Assertions.assertEquals(expected, settings.list, input);
        Assertions.assertEquals(expected, yaml(input).get("list"));
        Assertions.assertEquals(expected, yaml(Files.readString(directory.resolve("config.yml"))).get("list"));
        Assertions.assertEquals("kept", settings.next);
      }
    }
  }

  @Test
  void thousandEmptySequenceEntriesDoNotCreateRecursiveLists(@TempDir Path directory) {
    Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      String input = "list:\n" + "-\n".repeat(1000) + "next: kept\n";
      Settings settings = reload(directory, input);
      Assertions.assertEquals(1000, settings.list.size());
      Assertions.assertTrue(settings.list.stream().allMatch(value -> value == null));
      Assertions.assertEquals(yaml(input).get("list"), settings.list);
      Assertions.assertEquals(settings.list, yaml(Files.readString(directory.resolve("config.yml"))).get("list"));
      Assertions.assertEquals("kept", settings.next);
    });
  }

  @Test
  void preservesTrueNestedListsAndIndentationlessMapLists(@TempDir Path directory) throws Exception {
    String input = "list:\n-\n  - child\n  -\n- key:\n  - nested\n  -\n- last\nnext: kept\n";
    Settings settings = reload(directory, input);
    Assertions.assertEquals(yaml(input).get("list"), settings.list);
    Assertions.assertEquals(settings.list, yaml(Files.readString(directory.resolve("config.yml"))).get("list"));
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void choosesNewlineFlowMapsForTypedObjectAndNestedValues(@TempDir Path directory) throws Exception {
    String input = "map:\n  # map comment\n  {a: first, b: second}\nany:\n  {child: {a: nested}}\n"
        + "list:\n-\n  {a: entry}\nnext: kept\n";
    Settings settings = reload(directory, input);
    Assertions.assertEquals(Map.of("a", "first", "b", "second"), settings.map);
    Assertions.assertEquals(yaml(input).get("any"), settings.any);
    Assertions.assertEquals(yaml(input).get("list"), settings.list);
    Map<String, Object> written = yaml(Files.readString(directory.resolve("config.yml")));
    Assertions.assertEquals(settings.map, written.get("map"));
    Assertions.assertEquals(settings.any, written.get("any"));
    Assertions.assertEquals(settings.list, written.get("list"));
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void skipsUnknownNewlineFlowMapsWithoutLosingNextField() {
    Settings settings = new Settings();
    Assertions.assertFalse(settings.load(input("unknown:\n  {a: first, child: {b: nested}}\nnext: kept\n")));
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void distinguishesNullableTypedMapKeysFromQuotedNull(@TempDir Path directory) throws Exception {
    for (String entries : List.of("\n  null: default\n  'null': literal\n", " {null: default, 'null': literal}\n")) {
      String source = "map:" + entries + "numbers: {null: zero, '12': dozen}\nnext: kept\n";
      Settings settings = reload(directory, source);
      Map<String, String> expected = new LinkedHashMap<>();
      expected.put(null, "default");
      expected.put("null", "literal");
      Assertions.assertEquals(expected, settings.map);
      Assertions.assertEquals(expected, yaml(source).get("map"));
      Assertions.assertEquals(expected, yaml(Files.readString(directory.resolve("config.yml"))).get("map"));
      Assertions.assertEquals("zero", settings.numbers.get(null));
      Assertions.assertEquals("dozen", settings.numbers.get(12));
      Assertions.assertEquals(2, settings.numbers.size());
    }
  }

  @Test
  void invalidBooleanFieldsFailWithoutChangingStateOrFile(@TempDir Path directory) throws Exception {
    for (boolean safeMode : List.of(false, true)) {
      for (String field : List.of("enabled", "boxed")) {
        for (String value : List.of("treu", "123", "''", "'yes'", "'falsex'")) {
          Settings settings = new Settings();
          settings.setConfig(config(safeMode));
          assertRejectedReload(directory, settings, "next: changed\n" + field + ": " + value + "\n");
          Assertions.assertTrue(settings.enabled);
          Assertions.assertEquals(Boolean.TRUE, settings.boxed);
          Assertions.assertEquals("original", settings.next);
        }
      }
    }
  }

  @Test
  void invalidCharactersFailWithoutTruncationOrMutation(@TempDir Path directory) throws Exception {
    for (boolean safeMode : List.of(false, true)) {
      for (String field : List.of("letter", "boxed-letter")) {
        for (String value : List.of("ab", "''", "'ab'", "\"ab\"", "\"\\U0001F600\"", "\"a\\n\"")) {
          Settings settings = new Settings();
          settings.setConfig(config(safeMode));
          assertRejectedReload(directory, settings, "next: changed\n" + field + ": " + value + "\n");
          Assertions.assertEquals('z', settings.letter);
          Assertions.assertEquals(Character.valueOf('z'), settings.boxedLetter);
          Assertions.assertEquals("original", settings.next);
        }
      }
    }
  }

  @Test
  void booleanAndCharacterContainersUseStrictValidation() {
    for (String source : List.of("booleans: [true, treu]\n", "boolean-map: {a: 123}\n",
        "characters: ['a', 'ab']\n", "character-map: {a: ''}\n")) {
      Settings settings = new Settings();
      Assertions.assertThrows(IllegalStateException.class, () -> settings.load(input("next: changed\n" + source)), source);
      Assertions.assertEquals("original", settings.next);
      Assertions.assertEquals(List.of(true), settings.booleans);
      Assertions.assertEquals(List.of('z'), settings.characters);
    }
  }

  @Test
  void jsonAlsoRejectsInvalidBooleanAndCharacterValues(@TempDir Path directory) throws Exception {
    for (String source : List.of("{\"next\": \"changed\", \"enabled\": \"treu\"}",
        "{\"next\": \"changed\", \"boxed\": 123}", "{\"next\": \"changed\", \"letter\": \"ab\"}",
        "{\"next\": \"changed\", \"boxed-letter\": \"\"}")) {
      Path path = directory.resolve("config.json");
      Files.writeString(path, source);
      JsonSettings settings = new JsonSettings();
      Assertions.assertThrows(IllegalStateException.class, () -> settings.reload(path), source);
      Assertions.assertEquals(source, Files.readString(path));
      Assertions.assertEquals("original", settings.next);
      Assertions.assertTrue(settings.enabled);
      Assertions.assertEquals(Boolean.TRUE, settings.boxed);
      Assertions.assertEquals('z', settings.letter);
      Assertions.assertEquals(Character.valueOf('z'), settings.boxedLetter);
    }
  }

  @Test
  void validBooleanAndSingleCharacterScalarsRemainSupported() {
    Settings settings = new Settings();
    Assertions.assertTrue(settings.load(input("enabled: FALSE\nboxed: 'TrUe'\nletter: '\n  '\nboxed-letter: \"\\u0041\"\n"
        + "booleans: [true, false, null]\ncharacters: ['a', \"\\n\", null]\n")));
    Assertions.assertFalse(settings.enabled);
    Assertions.assertEquals(Boolean.TRUE, settings.boxed);
    Assertions.assertEquals(' ', settings.letter);
    Assertions.assertEquals(Character.valueOf('A'), settings.boxedLetter);
    Assertions.assertEquals(Arrays.asList(true, false, null), settings.booleans);
    Assertions.assertEquals(Arrays.asList('a', '\n', null), settings.characters);
  }

  @Test
  void quotedFoldingMatchesYamlWhitespaceAndClosingQuoteRules(@TempDir Path directory) throws Exception {
    for (String quoted : List.of("\"first  \n  second\"", "'first  \n  second'", "\"first\n  \"", "'first\n  '",
        "\"first  \n\n  second\"", "'first  \n\n  second'", "\"first\\   \n  second\"",
        "\"first\\t  \n  second\"", "\"first\\n  \n  second\"", "\"first\\\n  second\"")) {
      for (String newline : List.of("\n", "\r\n")) {
        String source = ("value: " + quoted + "\nnext: kept\n").replace("\n", newline);
        Settings settings = reload(directory, source);
        Assertions.assertEquals(yaml(source).get("value"), settings.value, source);
        Assertions.assertEquals(settings.value, yaml(Files.readString(directory.resolve("config.yml"))).get("value"));
        Assertions.assertEquals("kept", settings.next);
      }
    }
  }

  @Test
  void malformedPlainColonSeparationsCannotHideMissingFlowCommas(@TempDir Path directory) throws Exception {
    for (String source : List.of("{value: one next: two}", "{value: one next:\ttwo}", "{value: one next:\n two}",
        "value: one next: two\n", "any: {a: one b: two}\n", "unknown: {a: one b: two}\n")) {
      Settings settings = new Settings();
      Assertions.assertThrows(RuntimeException.class, () -> new Yaml().load(source), source);
      assertRejectedReload(directory, settings, source);
      Assertions.assertEquals("default", settings.value);
      Assertions.assertEquals("original", settings.next);
    }
  }

  @Test
  void plainAndFlowUrlsWithNonSeparatingColonsRemainValid(@TempDir Path directory) throws Exception {
    for (String source : List.of("value: https://example.test:8080/path\nnext: kept\n",
        "{value: https://example.test:8080/path, next: kept}", "{value: urn:example:item, next: kept}")) {
      Settings settings = reload(directory, source);
      Assertions.assertEquals(yaml(source).get("value"), settings.value);
      Assertions.assertEquals("kept", settings.next);
    }
  }

  @Test
  void unicodeSpacesFollowingPlainColonRemainScalarContent(@TempDir Path directory) throws Exception {
    for (String value : List.of("one:\u2002two", "one:\u2003two")) {
      for (String source : List.of("value: " + value + "\nnext: kept\n", "{value: " + value + ", next: kept}")) {
        Assertions.assertEquals(value, yaml(source).get("value"), source);
        Settings settings = reload(directory, source);
        Assertions.assertEquals(value, settings.value, source);
        Assertions.assertEquals("kept", settings.next, source);
        Assertions.assertEquals(value, yaml(Files.readString(directory.resolve("config.yml"))).get("value"));
        Settings restored = new Settings();
        Assertions.assertEquals(LoadResult.SUCCESS, restored.load(directory.resolve("config.yml")));
        Assertions.assertEquals(value, restored.value);
      }
    }
  }

  @Test
  void canonicalFieldCollisionsFailBeforeMutation(@TempDir Path directory) throws Exception {
    CollisionSettings settings = new CollisionSettings();
    assertRejectedReload(directory, settings, "next: changed\nport-1: replacement\n");
    Assertions.assertEquals("first", settings.port1);
    Assertions.assertEquals("second", settings.port_1);
    Assertions.assertEquals("original", settings.next);
  }

  @Test
  void finalVisibleFieldsParticipateInCanonicalCollisions(@TempDir Path directory) throws Exception {
    FinalCollisionSettings settings = new FinalCollisionSettings();
    assertRejectedReload(directory, settings, "port-1: replacement\n");
    Assertions.assertEquals("second", settings.port_1);
  }

  @Test
  void fallbackCollisionsFailBeforeMutation(@TempDir Path directory) throws Exception {
    AliasCollisionSettings settings = new AliasCollisionSettings();
    assertRejectedReload(directory, settings, "next: changed\nsecond: replacement\n");
    Assertions.assertEquals("first", settings.first);
    Assertions.assertEquals("second", settings.second);
    Assertions.assertEquals("original", settings.next);
  }

  @Test
  void ignoredFieldsDoNotStealActiveMappingsAndOwnFallbacksMayRepeat() {
    IgnoredCollisionSettings settings = new IgnoredCollisionSettings();
    Assertions.assertTrue(settings.load(input("port-1: changed\nvalue: loaded\n")));
    Assertions.assertEquals("changed", settings.port1);
    Assertions.assertEquals("loaded", settings.value);
    Assertions.assertEquals("ignored", settings.port_1);
    Assertions.assertEquals("ignored", settings.staticValue);
    Assertions.assertEquals("ignored", settings.transientValue);
  }

  private static Settings reload(Path directory, String source) throws Exception {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, source);
    Settings settings = new Settings();
    Assertions.assertEquals(LoadResult.SUCCESS, settings.reload(path), source);
    return settings;
  }

  private static void assertRejectedReload(Path directory, YamlSerializable settings, String source) throws Exception {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, source);
    Assertions.assertThrows(IllegalStateException.class, () -> settings.reload(path), source);
    Assertions.assertEquals(source, Files.readString(path));
  }

  private static BufferedReader input(String source) {
    return new BufferedReader(new StringReader(source));
  }

  private static Map<String, Object> yaml(String source) {
    return new Yaml().load(source);
  }

  private static SerializerConfig config(boolean safeMode) {
    return new SerializerConfig.Builder().setSafeMode(safeMode).setLogMissingFields(false).build();
  }

  private static class Settings extends YamlSerializable {

    private String value = "default";
    private String next = "original";
    private List<Object> list = new ArrayList<>();
    private Map<String, String> map = new LinkedHashMap<>();
    private Map<Integer, String> numbers = new LinkedHashMap<>();
    private Object any;
    private boolean enabled = true;
    private Boolean boxed = true;
    private char letter = 'z';
    private Character boxedLetter = 'z';
    private List<Boolean> booleans = new ArrayList<>(List.of(true));
    private Map<String, Boolean> booleanMap = new LinkedHashMap<>();
    private List<Character> characters = new ArrayList<>(List.of('z'));
    private Map<String, Character> characterMap = new LinkedHashMap<>();
  }

  private static class JsonSettings extends JsonSerializable {

    private String next = "original";
    private boolean enabled = true;
    private Boolean boxed = true;
    private char letter = 'z';
    private Character boxedLetter = 'z';
  }

  private static class CollisionSettings extends YamlSerializable {

    private String next = "original";
    private String port1 = "first";
    // CHECKSTYLE.SUPPRESS: MemberName - Intentionally collides after node-name conversion.
    private String port_1 = "second";
  }

  private static class FinalCollisionSettings extends YamlSerializable {

    private final String port1 = "first";
    // CHECKSTYLE.SUPPRESS: MemberName - Intentionally collides after node-name conversion.
    private String port_1 = "second";
  }

  private static class AliasCollisionSettings extends YamlSerializable {

    private String next = "original";
    @FallbackNodeNames({"second", "old"})
    private String first = "first";
    private String second = "second";
  }

  private static class IgnoredCollisionSettings extends YamlSerializable {

    private String port1 = "original";
    @Transient
    // CHECKSTYLE.SUPPRESS: MemberName - Ignored field intentionally shares the active node name.
    private String port_1 = "ignored";
    @FallbackNodeNames({"value"})
    private static String staticValue = "ignored";
    @FallbackNodeNames({"value"})
    private transient String transientValue = "ignored";
    @FallbackNodeNames({"value", "old", "old"})
    private String value = "original";
  }
}
