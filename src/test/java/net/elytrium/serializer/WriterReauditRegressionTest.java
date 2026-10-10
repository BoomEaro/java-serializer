package net.elytrium.serializer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.elytrium.serializer.annotations.Comment;
import net.elytrium.serializer.annotations.CommentValue;
import net.elytrium.serializer.annotations.FallbackNodeNames;
import net.elytrium.serializer.annotations.OverrideNameStyle;
import net.elytrium.serializer.annotations.Serializer;
import net.elytrium.serializer.annotations.Transient;
import net.elytrium.serializer.annotations.YamlStringStyle;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.language.object.AbstractSerializable;
import net.elytrium.serializer.language.object.JsonSerializable;
import net.elytrium.serializer.language.object.YamlSerializable;
import net.elytrium.serializer.language.reader.AbstractReader;
import net.elytrium.serializer.language.reader.JsonReader;
import net.elytrium.serializer.language.reader.YamlReader;
import net.elytrium.serializer.language.writer.AbstractWriter;
import net.elytrium.serializer.language.writer.JsonWriter;
import net.elytrium.serializer.language.writer.YamlWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class WriterReauditRegressionTest {

  @Test
  void annotatedTypesEncodeFieldsAndOwnerlessContainerValuesAndKeys() throws IOException {
    for (boolean json : List.of(false, true)) {
      String output = write(new TokenSettings(), json, SerializerConfig.DEFAULT);
      Assertions.assertEquals(Map.of("field", "type:abc", "values", List.of("type:abc"),
          "map", Map.of("type:key", "type:abc"), "nested", List.of(List.of("type:abc"))), parse(output, json), output);
      TokenSettings restored = new TokenSettings();
      reader(output, json).readSerializableObject(restored, TokenSettings.class);
      Assertions.assertEquals("abc", restored.field.text);
      Assertions.assertEquals("abc", restored.values.getFirst().text);
      Assertions.assertEquals("key", restored.map.keySet().iterator().next().text);
      Assertions.assertEquals("abc", restored.map.values().iterator().next().text);
      Assertions.assertEquals("abc", restored.nested.getFirst().getFirst().text);
    }
  }

  @Test
  void annotatedTypesEncodeRootNodes() throws IOException {
    for (boolean json : List.of(false, true)) {
      Assertions.assertEquals("type:abc", parse(write(new Token("abc"), json, SerializerConfig.DEFAULT), json));
    }
  }

  @Test
  void fieldSerializerOverridesClassSerializer() throws IOException {
    for (boolean json : List.of(false, true)) {
      String output = write(new FieldPrioritySettings(), json, SerializerConfig.DEFAULT);
      Assertions.assertEquals(Map.of("value", "field:abc"), parse(output, json), output);
      FieldPrioritySettings restored = new FieldPrioritySettings();
      reader(output, json).readSerializableObject(restored, FieldPrioritySettings.class);
      Assertions.assertEquals("abc", restored.value.text);
    }
  }

  @Test
  void sameTypeAnnotationRegisteredTooRunsOnceForOwnerlessValues() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new SameTypeCodec()).build();
    for (boolean json : List.of(false, true)) {
      Assertions.assertEquals(List.of(Map.of("text", "abc!")), parse(write(List.of(new SameTypeToken("abc")), json, config), json));
    }
  }

  @Test
  void finalAppendJsonCommentsTerminateAtRootAndNestedBoundaries() throws IOException {
    for (Object value : List.of(new CommentSettings(), Map.of("nested", new CommentSettings()), List.of(new CommentSettings(), "next"))) {
      String output = write(value, true, SerializerConfig.DEFAULT);
      Object expected = value instanceof Map ? Map.of("nested", Map.of("value", "text"))
          : value instanceof List ? List.of(Map.of("value", "text"), "next") : Map.of("value", "text");
      Assertions.assertEquals(expected, parse(output, true), output);
      if (value instanceof CommentSettings) {
        CommentSettings restored = new CommentSettings();
        reader(output, true).readSerializableObject(restored, CommentSettings.class);
        Assertions.assertEquals("text", restored.value);
      }
    }
  }

  @Test
  void singleQuotedUnicodeFormatterCharactersRemainActualCharacters() throws Exception {
    for (boolean allowUnicode : List.of(false, true)) {
      SerializerConfig config = new SerializerConfig.Builder().setAllowUnicode(allowUnicode).build();
      for (String value : List.of("co\u00adoperate", "a\u200bb", "a\u200fb", "\ud83d\udc69\u200d\ud83d\udcbb", "a'b\u200dc")) {
        Assertions.assertEquals(value, styled("single", value, config), value);
      }
    }
  }

  @Test
  void rememberedSingleQuotedUnicodeSurvivesLoadSaveReload() throws IOException {
    for (String value : List.of("co\u00adoperate", "\ud83d\udc69\u200d\ud83d\udcbb")) {
      RememberedSettings settings = new RememberedSettings();
      Assertions.assertTrue(settings.load(new BufferedReader(new StringReader("value: '" + value + "'\n"))));
      Assertions.assertEquals(value, settings.value);
      StringWriter output = new StringWriter();
      try (BufferedWriter buffer = new BufferedWriter(output)) {
        settings.save(buffer);
      }
      Assertions.assertEquals(Map.of("value", value), new Yaml().load(output.toString()), output.toString());
      RememberedSettings restored = new RememberedSettings();
      Assertions.assertTrue(restored.load(new BufferedReader(new StringReader(output.toString()))));
      Assertions.assertEquals(value, restored.value, output.toString());
    }
  }

  @Test
  void physicallyMultilineQuotesPreserveLogicalWhitespace() throws Exception {
    for (String style : List.of("single", "doubleMultiline")) {
      for (String separator : List.of("\n", "\r\n")) {
        SerializerConfig config = new SerializerConfig.Builder().setLineSeparator(separator).build();
        for (String value : List.of("line1\n indented", "line1 \nnext", "line1\n\tindented", "line1\n \nnext", " \n leading \n ",
            "line1\r\n next", "line1\n\nline2", "\nline1", "line1\n", "line1\n\n")) {
          Assertions.assertEquals(value, styled(style, value, config), style + ": " + value);
        }
      }
    }
  }

  @Test
  void rememberedQuotedStylesPreserveEditedMultilineWhitespaceOnSaveReload() throws IOException {
    for (String source : List.of("value: 'initial'\n", "value: \"line1\n\n  line2\"\n")) {
      RememberedSettings settings = new RememberedSettings();
      Assertions.assertTrue(settings.load(new BufferedReader(new StringReader(source))));
      settings.value = "line1 \n indented\n \nlast ";
      StringWriter output = new StringWriter();
      try (BufferedWriter buffer = new BufferedWriter(output)) {
        settings.save(buffer);
      }
      Assertions.assertEquals(Map.of("value", "line1 \n indented\n \nlast "), new Yaml().load(output.toString()), output.toString());
      RememberedSettings restored = new RememberedSettings();
      Assertions.assertTrue(restored.load(new BufferedReader(new StringReader(output.toString()))));
      Assertions.assertEquals("line1 \n indented\n \nlast ", restored.value, output.toString());
    }
  }

  @Test
  void uncomplicatedMultilineQuotesStillUsePhysicalLines() throws Exception {
    for (String style : List.of("single", "doubleMultiline")) {
      String output = styledOutput(style, "line1\nline2", SerializerConfig.DEFAULT);
      Assertions.assertFalse(output.contains("\\n"), output);
      Assertions.assertEquals(Map.of("value", "line1\nline2"), new Yaml().load(output), output);
    }
  }

  @Test
  void collidingVisiblePojoNamesFailBeforeAnyOutput() throws IOException {
    for (boolean json : List.of(false, true)) {
      StringWriter output = new StringWriter();
      try (BufferedWriter buffer = new BufferedWriter(output)) {
        AbstractWriter writer = json ? new JsonWriter(buffer) : new YamlWriter(buffer);
        Assertions.assertThrows(IllegalArgumentException.class, () -> writer.writeNode(new CollisionSettings(), null));
      }
      Assertions.assertEquals("", output.toString());
    }
  }

  @Test
  void ignoredPojoFieldsDoNotCollideWithVisibleFinalField() throws IOException {
    for (boolean json : List.of(false, true)) {
      Assertions.assertEquals(Map.of("foo-bar", "kept"), parse(write(new IgnoredCollisionSettings(), json, SerializerConfig.DEFAULT), json));
    }
  }

  @Test
  void fallbackAliasesCannotOverlapOtherVisibleCanonicalNamesOrAliases() throws IOException {
    for (boolean json : List.of(false, true)) {
      for (Object value : List.of(new AliasCollisionSettings(), new ReverseAliasCollisionSettings(), new SharedAliasCollisionSettings())) {
        StringWriter output = new StringWriter();
        try (BufferedWriter buffer = new BufferedWriter(output)) {
          AbstractWriter writer = json ? new JsonWriter(buffer) : new YamlWriter(buffer);
          Assertions.assertThrows(IllegalArgumentException.class, () -> writer.writeNode(value, null));
        }
        Assertions.assertEquals("", output.toString());
      }
    }
  }

  @Test
  void fallbackAliasCollisionsPreventReplacingExistingFiles(@TempDir Path directory) throws IOException {
    for (AbstractSerializable settings : List.of(new AliasCollisionSettings(), new JsonAliasCollisionSettings())) {
      Path path = directory.resolve(settings instanceof JsonSerializable ? "config.json" : "config.yml");
      String original = "preserve existing content\n";
      Files.writeString(path, original);
      Assertions.assertThrows(IllegalArgumentException.class, () -> settings.save(path));
      Assertions.assertEquals(original, Files.readString(path));
    }
  }

  @Test
  void redundantOwnAliasesAndIgnoredFieldAliasesRemainWritableAndReadable() throws IOException {
    for (boolean json : List.of(false, true)) {
      String output = write(new OwnAliasSettings(), json, SerializerConfig.DEFAULT);
      Assertions.assertEquals(Map.of("value", "kept"), parse(output, json), output);
      OwnAliasSettings restored = new OwnAliasSettings();
      restored.value = "changed";
      reader(output, json).readSerializableObject(restored, OwnAliasSettings.class);
      Assertions.assertEquals("kept", restored.value);
    }
  }

  private static String write(Object value, boolean json, SerializerConfig config) throws IOException {
    StringWriter output = new StringWriter();
    try (BufferedWriter buffer = new BufferedWriter(output)) {
      AbstractWriter writer = json ? new JsonWriter(buffer, config) : new YamlWriter(config, buffer);
      writer.writeNode(value, null);
    }
    return output.toString();
  }

  private static AbstractReader reader(String output, boolean json) {
    BufferedReader input = new BufferedReader(new StringReader(output));
    return json ? new JsonReader(input) : new YamlReader(input);
  }

  private static Object parse(String output, boolean json) throws IOException {
    if (!json) {
      return new Yaml().load(output);
    }
    JsonFactory factory = JsonFactory.builder().enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build();
    try (JsonParser parser = factory.createParser(output)) {
      parser.nextToken();
      Object result = readJson(parser);
      Assertions.assertNull(parser.nextToken(), output);
      return result;
    }
  }

  private static Object readJson(JsonParser parser) throws IOException {
    return switch (parser.currentToken()) {
      case START_OBJECT -> {
        Map<String, Object> map = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          String name = parser.currentName();
          parser.nextToken();
          map.put(name, readJson(parser));
        }
        yield map;
      }
      case START_ARRAY -> {
        List<Object> list = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          list.add(readJson(parser));
        }
        yield list;
      }
      case VALUE_STRING -> parser.getText();
      default -> throw new AssertionError("Unexpected JSON token: " + parser.currentToken());
    };
  }

  private static Object styled(String style, String value, SerializerConfig config) throws Exception {
    String output = styledOutput(style, value, config);
    Map<?, ?> independent = new Yaml().load(output);
    RememberedSettings restored = new RememberedSettings();
    Assertions.assertTrue(restored.load(new BufferedReader(new StringReader(output))));
    Assertions.assertEquals(value, restored.value, output);
    return independent.get("value");
  }

  private static String styledOutput(String style, String value, SerializerConfig config) throws Exception {
    Field owner = Styles.class.getDeclaredField(style);
    StringWriter output = new StringWriter();
    try (BufferedWriter buffer = new BufferedWriter(output)) {
      new YamlWriter(config, buffer).writeMapEntry(owner, "value", value, false, null);
    }
    return output.toString();
  }

  @Serializer(TokenCodec.class)
  private static class Token {
    private String text;

    private Token(String text) {
      this.text = text;
    }
  }

  private static class TokenCodec extends ClassSerializer<Token, String> {
    @Override
    public String serialize(Token value) {
      return "type:" + value.text;
    }

    @Override
    public Token deserialize(String value) {
      Assertions.assertTrue(value.startsWith("type:"), value);
      return new Token(value.substring(5));
    }
  }

  private static class FieldCodec extends ClassSerializer<Token, String> {
    @Override
    public String serialize(Token value) {
      return "field:" + value.text;
    }

    @Override
    public Token deserialize(String value) {
      Assertions.assertTrue(value.startsWith("field:"), value);
      return new Token(value.substring(6));
    }
  }

  private static class TokenSettings {
    private Token field = new Token("abc");
    private List<Token> values = List.of(new Token("abc"));
    private Map<Token, Token> map = Map.of(new Token("key"), new Token("abc"));
    private List<List<Token>> nested = List.of(List.of(new Token("abc")));
  }

  private static class FieldPrioritySettings {
    @Serializer(FieldCodec.class)
    private Token value = new Token("abc");
  }

  @Serializer(SameTypeCodec.class)
  private static class SameTypeToken {
    private String text;

    private SameTypeToken(String text) {
      this.text = text;
    }
  }

  private static class SameTypeCodec extends ClassSerializer<SameTypeToken, SameTypeToken> {
    @Override
    public SameTypeToken serialize(SameTypeToken value) {
      return new SameTypeToken(value.text + "!");
    }
  }

  private static class CommentSettings {
    @Comment(value = {@CommentValue("first"), @CommentValue("last")}, at = Comment.At.APPEND)
    private String value = "text";
  }

  private static class Styles {
    @YamlStringStyle(YamlWriter.StringStyle.SINGLE_QUOTED)
    private String single;
    @YamlStringStyle(YamlWriter.StringStyle.DOUBLE_QUOTED_MULTILINE)
    private String doubleMultiline;
  }

  private static class RememberedSettings extends YamlSerializable {
    private String value;
  }

  private static class CollisionSettings {
    @Comment(@CommentValue("must not write before validation"))
    private String fooBar = "first";
    @SuppressWarnings("checkstyle:MemberName")
    @OverrideNameStyle(field = NameStyle.SNAKE_CASE)
    private final String foo_bar = "second";
  }

  private static class IgnoredCollisionSettings {
    private final String fooBar = "kept";
    @OverrideNameStyle(field = NameStyle.SNAKE_CASE)
    private static String foo_bar = "static";
    @Transient
    @SuppressWarnings("checkstyle:MemberName")
    @OverrideNameStyle(field = NameStyle.MACRO_CASE)
    private String FOO_BAR = "annotation transient";
    @OverrideNameStyle(field = NameStyle.COBOL_CASE)
    private transient String fooBAR = "modifier transient";
  }

  private static class AliasCollisionSettings extends YamlSerializable {
    @Comment(@CommentValue("must not be emitted"))
    @FallbackNodeNames({"second"})
    private String first = "one";
    private final String second = "two";
  }

  private static class JsonAliasCollisionSettings extends JsonSerializable {
    @FallbackNodeNames({"second"})
    private String first = "one";
    private final String second = "two";
  }

  private static class ReverseAliasCollisionSettings {
    private String first = "one";
    @FallbackNodeNames({"first"})
    private String second = "two";
  }

  private static class SharedAliasCollisionSettings {
    @FallbackNodeNames({"shared"})
    private String first = "one";
    @FallbackNodeNames({"shared"})
    private String second = "two";
  }

  private static class OwnAliasSettings {
    @FallbackNodeNames({"value", "old", "old"})
    private String value = "kept";
    @FallbackNodeNames({"value", "old"})
    private static String staticValue = "ignored";
    @FallbackNodeNames({"value", "old"})
    private transient String transientValue = "ignored";
    @Transient
    @FallbackNodeNames({"value", "old"})
    private String annotationTransient = "ignored";
  }
}
