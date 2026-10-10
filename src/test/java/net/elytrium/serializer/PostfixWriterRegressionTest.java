package net.elytrium.serializer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.elytrium.serializer.annotations.Comment;
import net.elytrium.serializer.annotations.CommentValue;
import net.elytrium.serializer.annotations.NewLine;
import net.elytrium.serializer.annotations.Serializer;
import net.elytrium.serializer.custom.ClassSerializer;
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

class PostfixWriterRegressionTest {

  @Test
  void firstPrependCommentKeepsSingleFieldListItemInItsMapping() throws IOException {
    String output = write(List.of(new CommentItem()), false, SerializerConfig.DEFAULT);
    Assertions.assertEquals(List.of(Map.of("value", "kept")), parse(output, false), output);
  }

  @Test
  void firstPrependCommentKeepsFollowingFieldsAndNestedListsIndented() throws IOException {
    for (Object value : List.of(List.of(new TwoFieldItem()), List.of(List.of(new TwoFieldItem())))) {
      Object expected = value instanceof List<?> list && list.getFirst() instanceof List
          ? List.of(List.of(Map.of("value", "kept", "second", "next")))
          : List.of(Map.of("value", "kept", "second", "next"));
      String output = write(value, false, SerializerConfig.DEFAULT);
      Assertions.assertEquals(expected, parse(output, false), output);
    }
  }

  @Test
  void firstNewLineKeepsListItemIndentation() throws IOException {
    String output = write(List.of(new NewLineItem()), false, SerializerConfig.DEFAULT);
    Assertions.assertEquals(List.of(Map.of("value", "kept", "second", "next")), parse(output, false), output);
  }

  @Test
  void commentPositionsAndRootAndJsonMappingsRetainTheirValues() throws IOException {
    for (boolean json : List.of(false, true)) {
      for (Object item : List.of(new CommentItem(), new SameLineItem(), new AppendItem(), new NewLineItem())) {
        Map<String, String> expected = item instanceof NewLineItem ? Map.of("value", "kept", "second", "next") : Map.of("value", "kept");
        Assertions.assertEquals(expected, parse(write(item, json, SerializerConfig.DEFAULT), json));
        Assertions.assertEquals(List.of(expected), parse(write(List.of(item), json, SerializerConfig.DEFAULT), json));
      }
    }
  }

  @Test
  void reloadCommentedListPreservesDiskStructureAndCanReloadAgain(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("comments.yml");
    Files.writeString(path, "list:\n  - value: kept\n");
    CommentSettings settings = new CommentSettings();
    Assertions.assertEquals(LoadResult.SUCCESS, settings.reload(path));
    Assertions.assertEquals("kept", settings.list.getFirst().value);
    String output = Files.readString(path);
    Assertions.assertEquals(Map.of("list", List.of(Map.of("value", "kept"))), parse(output, false), output);
    CommentSettings restored = new CommentSettings();
    Assertions.assertEquals(LoadResult.SUCCESS, restored.reload(path));
    Assertions.assertEquals("kept", restored.list.getFirst().value);
  }

  @Test
  void enumDeclaringClassAnnotationEncodesNormalAndBodyConstantsInContainers() throws IOException {
    for (boolean json : List.of(false, true)) {
      String output = write(new EnumSettings(), json, SerializerConfig.DEFAULT);
      Assertions.assertEquals(Map.of("values", List.of("type:NORMAL", "type:BODY"),
          "map", Map.of("type:NORMAL", "type:BODY", "type:BODY", "type:NORMAL")), parse(output, json), output);
      EnumSettings restored = new EnumSettings();
      reader(output, json, SerializerConfig.DEFAULT).readSerializableObject(restored, EnumSettings.class);
      Assertions.assertEquals(List.of(AnnotatedEnum.NORMAL, AnnotatedEnum.BODY), restored.values);
      Assertions.assertEquals(Map.of(AnnotatedEnum.NORMAL, AnnotatedEnum.BODY, AnnotatedEnum.BODY, AnnotatedEnum.NORMAL), restored.map);
    }
  }

  @Test
  void enumDeclaringClassAnnotationEncodesRootBodyConstant() throws IOException {
    for (boolean json : List.of(false, true)) {
      for (AnnotatedEnum value : AnnotatedEnum.values()) {
        String output = write(value, json, SerializerConfig.DEFAULT);
        Assertions.assertEquals("type:" + value.name(), parse(output, json), output);
        Assertions.assertEquals(value, reader(output, json, SerializerConfig.DEFAULT).readByType(AnnotatedEnum.class));
      }
    }
  }

  @Test
  void enumFieldAnnotationStillOverridesDeclaringClassAnnotation() throws IOException {
    for (boolean json : List.of(false, true)) {
      String output = write(new EnumPrioritySettings(), json, SerializerConfig.DEFAULT);
      Assertions.assertEquals(Map.of("value", "field:BODY"), parse(output, json), output);
    }
  }

  @Test
  void primitiveOwnerAndWrapperUseTheirExactRegisteredSerializers() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder()
        .registerSerializer(new PrimitiveCodec()).registerSerializer(new WrapperCodec()).build();
    for (boolean json : List.of(false, true)) {
      String output = write(new PrimitiveSettings(), json, config);
      Assertions.assertEquals(Map.of("primitive", "primitive:8", "wrapper", "wrapper:9"), parse(output, json), output);
      PrimitiveSettings restored = new PrimitiveSettings();
      restored.primitive = 0;
      restored.wrapper = 0;
      reader(output, json, config).readSerializableObject(restored, PrimitiveSettings.class);
      Assertions.assertEquals(8, restored.primitive);
      Assertions.assertEquals(9, restored.wrapper);
    }
  }

  @Test
  void forbiddenYamlCharactersAreEscapedInEveryStringStyle() throws IOException {
    for (boolean allowUnicode : List.of(false, true)) {
      SerializerConfig config = new SerializerConfig.Builder().setAllowUnicode(allowUnicode).build();
      for (YamlWriter.StringStyle style : YamlWriter.StringStyle.values()) {
        for (int codePoint : List.of(0x1C, 0x1D, 0x1E, 0x1F, 0x7F, 0x80, 0x84, 0x86, 0x9F, 0xFFFE, 0xFFFF)) {
          for (String suffix : List.of("", "\nlast")) {
            String value = "before" + (char) codePoint + "after" + suffix;
            String output = styled(style, value, config);
            String context = style + " U+" + Integer.toHexString(codePoint) + " unicode=" + allowUnicode + ": " + output;
            Assertions.assertFalse(output.indexOf((char) codePoint) >= 0, context);
            Assertions.assertEquals(Map.of("value", value), new Yaml().load(output), context);
            Assertions.assertEquals(Map.of("value", value), new YamlReader(new BufferedReader(new StringReader(output)), config)
                .readMap(String.class, String.class), context);
          }
        }
      }
    }
  }

  @Test
  void printableUnicodeAndRegularMultilineStringsKeepTheirStyle() throws IOException {
    for (boolean allowUnicode : List.of(false, true)) {
      SerializerConfig config = new SerializerConfig.Builder().setAllowUnicode(allowUnicode).build();
      for (YamlWriter.StringStyle style : YamlWriter.StringStyle.values()) {
        String value = "café\n日本語\n😀";
        String output = styled(style, value, config);
        Assertions.assertEquals(Map.of("value", value), new Yaml().load(output), output);
        if (style.name().startsWith("MULTILINE_")) {
          Assertions.assertTrue(output.startsWith("value: |") || output.startsWith("value: >"), output);
        }
      }
    }
  }

  private static String styled(YamlWriter.StringStyle style, String value, SerializerConfig config) throws IOException {
    StringWriter output = new StringWriter();
    try (BufferedWriter buffer = new BufferedWriter(output)) {
      YamlWriter writer = new YamlWriter(config, buffer);
      writer.writeNodeName("value");
      style.write(writer, value);
    }
    return output.toString();
  }

  private static String write(Object value, boolean json, SerializerConfig config) throws IOException {
    StringWriter output = new StringWriter();
    try (BufferedWriter buffer = new BufferedWriter(output)) {
      AbstractWriter writer = json ? new JsonWriter(buffer, config) : new YamlWriter(config, buffer);
      writer.writeNode(value, null);
    }
    return output.toString();
  }

  private static AbstractReader reader(String output, boolean json, SerializerConfig config) {
    BufferedReader input = new BufferedReader(new StringReader(output));
    return json ? new JsonReader(input, config) : new YamlReader(input, config);
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
      default -> throw new AssertionError("Unexpected token: " + parser.currentToken());
    };
  }

  private static class CommentItem {
    @Comment(@CommentValue("help"))
    private String value = "kept";
  }

  private static class TwoFieldItem {
    @Comment(@CommentValue("help"))
    private String value = "kept";
    private String second = "next";
  }

  private static class NewLineItem {
    @NewLine(amount = 2)
    private String value = "kept";
    private String second = "next";
  }

  private static class SameLineItem {
    @Comment(value = @CommentValue("help"), at = Comment.At.SAME_LINE)
    private String value = "kept";
  }

  private static class AppendItem {
    @Comment(value = @CommentValue("help"), at = Comment.At.APPEND)
    private String value = "kept";
  }

  private static class CommentSettings extends YamlSerializable {
    private List<CommentItem> list = new ArrayList<>();
  }

  @Serializer(EnumCodec.class)
  private enum AnnotatedEnum {
    NORMAL,
    BODY {
      @Override
      public String toString() {
        return "custom body";
      }
    }
  }

  private static class EnumCodec extends ClassSerializer<AnnotatedEnum, String> {
    @Override
    public String serialize(AnnotatedEnum value) {
      return "type:" + value.name();
    }

    @Override
    public AnnotatedEnum deserialize(String value) {
      Assertions.assertTrue(value.startsWith("type:"), value);
      return AnnotatedEnum.valueOf(value.substring(5));
    }
  }

  private static class EnumFieldCodec extends ClassSerializer<AnnotatedEnum, String> {
    @Override
    public String serialize(AnnotatedEnum value) {
      return "field:" + value.name();
    }
  }

  private static class EnumSettings {
    private List<AnnotatedEnum> values = List.of(AnnotatedEnum.NORMAL, AnnotatedEnum.BODY);
    private Map<AnnotatedEnum, AnnotatedEnum> map = Map.of(AnnotatedEnum.NORMAL, AnnotatedEnum.BODY, AnnotatedEnum.BODY, AnnotatedEnum.NORMAL);
  }

  private static class EnumPrioritySettings {
    @Serializer(EnumFieldCodec.class)
    private AnnotatedEnum value = AnnotatedEnum.BODY;
  }

  private static class PrimitiveSettings {
    private int primitive = 8;
    private Integer wrapper = 9;
  }

  private static class PrimitiveCodec extends ClassSerializer<Integer, String> {
    private PrimitiveCodec() {
      super(int.class, String.class);
    }

    @Override
    public String serialize(Integer value) {
      return "primitive:" + value;
    }

    @Override
    public Integer deserialize(String value) {
      Assertions.assertTrue(value.startsWith("primitive:"), value);
      return Integer.parseInt(value.substring(10));
    }
  }

  private static class WrapperCodec extends ClassSerializer<Integer, String> {
    @Override
    public String serialize(Integer value) {
      return "wrapper:" + value;
    }

    @Override
    public Integer deserialize(String value) {
      Assertions.assertTrue(value.startsWith("wrapper:"), value);
      return Integer.parseInt(value.substring(8));
    }
  }
}
