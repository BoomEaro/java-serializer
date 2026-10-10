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
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.language.object.YamlSerializable;
import net.elytrium.serializer.language.reader.YamlReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class PostfixReaderRegressionTest {

  @Test
  void objectListRecognizesQuotedFirstMappingKeys(@TempDir Path directory) throws Exception {
    for (String key : List.of("null", "true", "a:b", "")) {
      String source = "list:\n  - \"" + key + "\": x\n    sibling: kept\n  - null\nnext: kept\n";
      Settings settings = new Settings();
      reload(directory, source, settings);
      Assertions.assertEquals(Arrays.asList(Map.of(key, "x", "sibling", "kept"), null), settings.list);
      Assertions.assertEquals("kept", settings.next);
      Assertions.assertEquals(yaml(source).get("list"), yaml(Files.readString(directory.resolve("config.yml"))).get("list"));
      TypedMappings typed = new TypedMappings();
      reload(directory, source, typed);
      Assertions.assertEquals(settings.list, typed.list);
    }
  }

  @Test
  void compactObjectListsKeepTheirOwnIndentation(@TempDir Path directory) throws Exception {
    String source = "list:\n  - - first\n    - second\n  - third\n  - null\nnext: kept\n";
    Settings settings = new Settings();
    reload(directory, source, settings);
    Assertions.assertEquals(Arrays.asList(List.of("first", "second"), "third", null), settings.list);
    Assertions.assertEquals("kept", settings.next);
    Assertions.assertEquals(yaml(source).get("list"), yaml(Files.readString(directory.resolve("config.yml"))).get("list"));
    TypedLists typed = new TypedLists();
    reload(directory, "list:\n  - - first\n    - second\n  - - third\nnext: kept\n", typed);
    Assertions.assertEquals(List.of(List.of("first", "second"), List.of("third")), typed.list);
  }

  @Test
  void objectFieldsRecognizeActualNodeAfterNewline(@TempDir Path directory) throws Exception {
    for (String node : List.of("abc", "[a, b]", "{a: b}", "\"abc\"", "'abc'", "|-\n    abc",
        ">-\n    abc", "\"null\": x", "- - first\n    - second\n  - third")) {
      String source = "any:\n  " + node + "\nnext: kept\n";
      Settings settings = new Settings();
      reload(directory, source, settings);
      Assertions.assertEquals(yaml(source).get("any"), settings.any, source);
      Assertions.assertEquals("kept", settings.next, source);
      Assertions.assertEquals(settings.any, yaml(Files.readString(directory.resolve("config.yml"))).get("any"));
    }
  }

  @Test
  void objectMapValuesRecognizeQuotedMappingsAndNestedLists(@TempDir Path directory) throws Exception {
    for (String node : List.of("\"true\": x", "- - first\n      - second\n    - third")) {
      String source = "objects:\n  item:\n    " + node + "\n  absent:\n  sibling: kept\nnext: kept\n";
      Settings settings = new Settings();
      reload(directory, source, settings);
      Assertions.assertEquals(yaml(source).get("objects"), settings.objects, source);
      Assertions.assertEquals("kept", settings.next);
    }
  }

  @Test
  void omittedFlowMapValuesRemainNullWhileQuotedEmptyValuesRemainEmpty(@TempDir Path directory) throws Exception {
    for (String source : List.of("map: {a: , b: present, empty: '', double: \"\", last:}\nnext: kept\n",
        "map: {a:, b: present, empty: '',}\nnext: kept\n")) {
      Settings settings = new Settings();
      reload(directory, source, settings);
      Assertions.assertEquals(yaml(source).get("map"), settings.map);
      Assertions.assertNull(settings.map.get("a"));
      Assertions.assertEquals("", settings.map.get("empty"));
      Assertions.assertEquals(settings.map, yaml(Files.readString(directory.resolve("config.yml"))).get("map"));
    }
    Settings settings = new Settings();
    reload(directory, "objects: {a: , b: '',}\nnumbers: {a: , b: 7, last:}\nnext: kept\n", settings);
    Assertions.assertTrue(settings.objects.containsKey("a"));
    Assertions.assertNull(settings.objects.get("a"));
    Assertions.assertEquals("", settings.objects.get("b"));
    Assertions.assertTrue(settings.numbers.containsKey("a"));
    Assertions.assertNull(settings.numbers.get("a"));
    Assertions.assertNull(settings.numbers.get("last"));
    Assertions.assertEquals(7, settings.numbers.get("b"));
  }

  @Test
  void omittedPrimitiveFlowValueRejectsReloadWithoutChangingFileOrFields(@TempDir Path directory) throws Exception {
    Settings settings = new Settings();
    String source = "{next: changed, primitive: ,}";
    rejectedReload(directory, source, settings);
    Assertions.assertEquals("original", settings.next);
    Assertions.assertEquals(9, settings.primitive);
  }

  @Test
  void quotedNewlinesPreserveUnicodeContentAfterPhysicalIndentation(@TempDir Path directory) throws Exception {
    for (String content : List.of("\u00a0", "\u2003", "\u2029")) {
      for (String quote : List.of("\"", "'")) {
        String source = "text: " + quote + "first\n  " + content + "second" + quote + "\nnext: kept\n";
        Settings settings = new Settings();
        reload(directory, source, settings);
        Assertions.assertEquals(yaml(source).get("text"), settings.text, source);
        Assertions.assertTrue(settings.text.contains(content));
        Assertions.assertEquals(settings.text, yaml(Files.readString(directory.resolve("config.yml"))).get("text"));
        Assertions.assertEquals("kept", settings.next);
      }
    }
  }

  @Test
  void escapedNewlinesPreserveUnicodeContentAfterPhysicalIndentation(@TempDir Path directory) throws Exception {
    for (String content : List.of("\u00a0", "\u2003", "\u2029")) {
      String source = "text: \"first\\\n  " + content + "second\"\nnext: kept\n";
      Settings settings = new Settings();
      reload(directory, source, settings);
      Assertions.assertEquals(yaml(source).get("text"), settings.text);
      Assertions.assertTrue(settings.text.contains(content));
      Assertions.assertEquals(settings.text, yaml(Files.readString(directory.resolve("config.yml"))).get("text"));
    }
  }

  @Test
  void ordinaryQuotedFoldsAndEscapedLineBreaksKeepExistingSemantics(@TempDir Path directory) throws Exception {
    for (String node : List.of("\"first\n  second\"", "'first\n  second'", "\"first\\\n  second\"", "\"first\n\n  second\"")) {
      String source = "text: " + node + "\nnext: kept\n";
      Settings settings = new Settings();
      reload(directory, source, settings);
      Assertions.assertEquals(yaml(source).get("text"), settings.text, source);
      Assertions.assertEquals("kept", settings.next);
    }
  }

  @Test
  void extraFlowCommasRejectReloadWithoutInventingData(@TempDir Path directory) throws Exception {
    for (String node : List.of("list: [,]", "list: [,a]", "list: [a,,b]", "list: [a,,]", "map: {,}",
        "map: {,a: b}", "map: {a: b, , c: d}", "map: {a: b,,}")) {
      String source = "next: changed\n" + node + "\n";
      Assertions.assertThrows(RuntimeException.class, () -> new Yaml().load(source));
      Settings settings = new Settings();
      Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> rejectedReload(directory, source, settings));
      Assertions.assertEquals("original", settings.next);
      Assertions.assertTrue(settings.list.isEmpty());
      Assertions.assertTrue(settings.map.isEmpty());
    }
  }

  @Test
  void emptyAndTrailingCommaFlowCollectionsRemainValid(@TempDir Path directory) throws Exception {
    for (String source : List.of("list: []\nmap: {}\nnext: kept\n", "list: [a,]\nmap: {a: b,}\nnext: kept\n")) {
      Settings settings = new Settings();
      reload(directory, source, settings);
      Assertions.assertEquals(yaml(source).get("list"), settings.list);
      Assertions.assertEquals(yaml(source).get("map"), settings.map);
      Assertions.assertEquals("kept", settings.next);
    }
  }

  @Test
  void objectKeyRegistrationMatchesScalarAndValueConversion() {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new ObjectWireSerializer()).build();
    Assertions.assertEquals("abc", new YamlReader(input("'wire:abc'"), config).readByType(Object.class));
    Map<Object, Object> decoded = new YamlReader(input("{'wire:abc': 'wire:def', null: null}"), config)
        .readMap(null, new LinkedHashMap<>(), Object.class, Object.class);
    Assertions.assertEquals("def", decoded.get("abc"));
    Assertions.assertTrue(decoded.containsKey(null));
    Assertions.assertNull(decoded.get(null));
    Assertions.assertEquals(2, decoded.size());
  }

  @Test
  void unregisteredObjectKeysKeepQuotedAndPlainScalarTypes() {
    Map<Object, Object> decoded = new YamlReader(input("{'true': quoted, true: plain, '12': quotedNumber, 12: number, null: absent}"))
        .readMap(null, new LinkedHashMap<>(), Object.class, Object.class);
    Assertions.assertEquals("quoted", decoded.get("true"));
    Assertions.assertEquals("plain", decoded.get(true));
    Assertions.assertEquals("quotedNumber", decoded.get("12"));
    Assertions.assertEquals("number", decoded.get(12L));
    Assertions.assertEquals("absent", decoded.get(null));
    Assertions.assertEquals(5, decoded.size());
  }

  @Test
  void quotedMappingLookaheadDoesNotAcceptMalformedQuotes(@TempDir Path directory) throws Exception {
    for (String source : List.of("list:\n  - 'unterminated\n", "any:\n  \"bad\\q\": x\n", "list:\n  - 'key'x: value\n")) {
      rejectedReload(directory, source, new Settings());
    }
  }

  private static void reload(Path directory, String source, YamlSerializable settings) throws Exception {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, source);
    Assertions.assertEquals(LoadResult.SUCCESS, settings.reload(path), source);
  }

  private static void rejectedReload(Path directory, String source, YamlSerializable settings) throws Exception {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, source);
    Assertions.assertThrows(RuntimeException.class, () -> settings.reload(path), source);
    Assertions.assertEquals(source, Files.readString(path));
  }

  private static BufferedReader input(String source) {
    return new BufferedReader(new StringReader(source));
  }

  private static Map<String, Object> yaml(String source) {
    return new Yaml().load(source);
  }

  private static class Settings extends YamlSerializable {

    private List<Object> list = new ArrayList<>();
    private Map<String, String> map = new LinkedHashMap<>();
    private Map<String, Object> objects = new LinkedHashMap<>();
    private Map<String, Integer> numbers = new LinkedHashMap<>();
    private Object any;
    private String text = "original";
    private String next = "original";
    private int primitive = 9;
  }

  private static class TypedMappings extends YamlSerializable {

    private List<Map<String, String>> list = new ArrayList<>();
    private String next = "original";
  }

  private static class TypedLists extends YamlSerializable {

    private List<List<String>> list = new ArrayList<>();
    private String next = "original";
  }

  private static class ObjectWireSerializer extends ClassSerializer<Object, String> {

    @Override
    public String serialize(Object value) {
      return "wire:" + value;
    }

    @Override
    public Object deserialize(String value) {
      return value.substring("wire:".length());
    }
  }
}
