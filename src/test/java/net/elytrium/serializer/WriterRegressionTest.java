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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.elytrium.serializer.annotations.Comment;
import net.elytrium.serializer.annotations.CommentValue;
import net.elytrium.serializer.annotations.YamlStringStyle;
import net.elytrium.serializer.language.object.YamlSerializable;
import net.elytrium.serializer.language.reader.YamlReader;
import net.elytrium.serializer.language.writer.AbstractWriter;
import net.elytrium.serializer.language.writer.JsonWriter;
import net.elytrium.serializer.language.writer.YamlWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class WriterRegressionTest {

  @Test
  void jsonEscapesKeysAndAllStringControls() throws IOException {
    for (String value : List.of("a\\b", "a\"b", "a\nb", "\0\1\7\b\t\n\r\f\u0085\u00a0\u2028\u2029")) {
      String output = WriterRegressionTest.json(Map.of(value, value));
      try (JsonParser parser = new JsonFactory().createParser(output)) {
        Assertions.assertEquals(JsonToken.START_OBJECT, parser.nextToken(), output);
        Assertions.assertEquals(JsonToken.FIELD_NAME, parser.nextToken(), output);
        Assertions.assertEquals(value, parser.currentName(), output);
        Assertions.assertEquals(JsonToken.VALUE_STRING, parser.nextToken(), output);
        Assertions.assertEquals(value, parser.getText(), output);
        Assertions.assertEquals(JsonToken.END_OBJECT, parser.nextToken(), output);
        Assertions.assertNull(parser.nextToken(), output);
      }
    }
  }

  @Test
  void jsonQuotesCharactersAndEnums() throws IOException {
    for (Object value : List.of('x', '[', '\1', Example.RED)) {
      String output = WriterRegressionTest.json(value);
      try (JsonParser parser = new JsonFactory().createParser(output)) {
        Assertions.assertEquals(JsonToken.VALUE_STRING, parser.nextToken(), output);
        Assertions.assertEquals(value.toString(), parser.getText(), output);
      }
    }
  }

  @Test
  void jsonRejectsNonfiniteNumbers() {
    for (Number value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Float.NaN, Float.POSITIVE_INFINITY)) {
      Assertions.assertThrows(IllegalArgumentException.class, () -> WriterRegressionTest.json(value));
    }
  }

  @Test
  void yamlQuotesStructuralAndTypeAmbiguousKeys() throws IOException {
    for (String key : List.of("*a", "[a]", "&a", "null", "true", "01", ":", "?", "a: b", "a\nb")) {
      String output = WriterRegressionTest.yaml(Map.of(key, "x"));
      Assertions.assertEquals(Map.of(key, "x"), new Yaml().load(output), output);
    }
  }

  @Test
  void yamlQuotesAllCharacterValues() throws IOException {
    for (char value : new char[]{'[', ':', '|', '\1', 'x'}) {
      String output = WriterRegressionTest.yaml(Map.of("value", value));
      Assertions.assertEquals(Map.of("value", String.valueOf(value)), new Yaml().load(output), output);
    }
  }

  @Test
  void yamlPreservesNumericAndStringMapKeysAsDistinctScalars() throws IOException {
    Map<Object, String> values = new LinkedHashMap<>();
    values.put(123L, "number");
    values.put("123", "string");
    String output = WriterRegressionTest.yaml(values);
    Map<?, ?> independent = new Yaml().load(output);
    Assertions.assertEquals(2, independent.size(), output);
    Assertions.assertEquals("number", independent.get(123), output);
    Assertions.assertEquals("string", independent.get("123"), output);
    Assertions.assertEquals(values, new YamlReader(new BufferedReader(new StringReader(output))).readMap(Object.class, String.class), output);
  }

  @Test
  void yamlPreservesBooleanAndNullMapKeysAsDistinctScalars() throws IOException {
    Map<Object, String> values = new LinkedHashMap<>();
    values.put(true, "boolean");
    values.put("true", "string boolean");
    values.put(null, "null key");
    values.put("null", "string null");
    String output = WriterRegressionTest.yaml(values);
    Assertions.assertEquals(values, new Yaml().load(output), output);
    Assertions.assertEquals(values, new YamlReader(new BufferedReader(new StringReader(output))).readMap(Object.class, String.class), output);
  }

  @Test
  void yamlPreservesDistinctIntegerAndFloatingPointKeyScalars() throws IOException {
    Map<Object, String> values = new LinkedHashMap<>();
    values.put(1L, "integer");
    values.put(1.0, "float");
    values.put(-0.0, "negative zero");
    values.put(0.0, "positive zero");
    String output = WriterRegressionTest.yaml(values);
    Map<?, ?> independent = new Yaml().load(output);
    Assertions.assertEquals(4, independent.size(), output);
    Assertions.assertEquals("integer", independent.get(1), output);
    Assertions.assertEquals("float", independent.get(1.0), output);
    Assertions.assertEquals("negative zero", independent.get(-0.0), output);
    Assertions.assertEquals("positive zero", independent.get(0.0), output);
    Assertions.assertEquals(values, new YamlReader(new BufferedReader(new StringReader(output))).readMap(Object.class, String.class), output);
  }

  @Test
  void jsonRejectsCollidingStringifiedMapKeys() {
    Map<Object, String> values = new LinkedHashMap<>();
    values.put(123L, "number");
    values.put("123", "string");
    Assertions.assertThrows(IllegalArgumentException.class, () -> WriterRegressionTest.json(values));
  }

  @Test
  void yamlRejectsMapKeysThatEncodeAsTheSameString() {
    Map<Object, String> values = new LinkedHashMap<>();
    values.put('x', "character");
    values.put("x", "string");
    Assertions.assertThrows(IllegalArgumentException.class, () -> WriterRegressionTest.yaml(values));
  }

  @Test
  void singleQuotedFallbackDoesNotDuplicateApostrophe() throws Exception {
    Assertions.assertEquals("a'b\\c", WriterRegressionTest.parseStyled("single", "a'b\\c", "\n"));
  }

  @Test
  void blockStylesPreserveLiteralBackslashesAndTabs() throws Exception {
    for (String style : List.of("literal", "folded", "literalKept", "foldedKept", "literalStripped", "foldedStripped")) {
      Assertions.assertEquals("C:\\temp\tfile\nnext", WriterRegressionTest.parseStyled(style, "C:\\temp\tfile\nnext", "\n"), style);
    }
  }

  @Test
  void blockStylesPreserveTrailingLinefeedsWithLfAndCrlfOutput() throws Exception {
    for (String separator : List.of("\n", "\r\n")) {
      for (String style : List.of("literal", "folded", "literalKept", "foldedKept")) {
        for (String value : List.of("a", "a\n", "a\n\n", "a\n\n\n", "\n", "\n\n", "a\nb\n\n")) {
          Assertions.assertEquals(value, WriterRegressionTest.parseStyled(style, value, separator), style + " / " + value);
        }
      }
    }
  }

  @Test
  void blockStylesPreserveLeadingSpacesAndBlankLines() throws Exception {
    for (String style : List.of("literal", "folded", "literalKept", "foldedKept")) {
      for (String value : List.of(" a\nb", " a", "\n a\nb", "a\n\nb", "a\n b\nc", "a\n\n b\nc")) {
        Assertions.assertEquals(value, WriterRegressionTest.parseStyled(style, value, "\n"), style + " / " + value);
      }
    }
  }

  @Test
  void blockStylesPutSameLineCommentsOnHeader() throws Exception {
    for (String style : List.of("literal", "folded")) {
      Assertions.assertEquals("text", WriterRegressionTest.parseStyled(style, "text", "\n"), style);
    }
  }

  @Test
  void strippedStylesRejectLogicalTrailingLfWithCrlfOutput() {
    for (String style : List.of("literalStripped", "foldedStripped")) {
      Assertions.assertThrows(IllegalStateException.class, () -> WriterRegressionTest.parseStyled(style, "a\n", "\r\n"));
    }
  }

  @Test
  void blockValuesPreserveTrailingNewlinesAtDocumentAndNestedBoundaries() throws Exception {
    for (String value : List.of("a", "a\n", "a\n\n", "\n", "\n\n", " a\nb")) {
      EndSettings settings = new EndSettings();
      settings.value = value;
      Assertions.assertEquals(Map.of("value", value), new Yaml().load(WriterRegressionTest.yaml(settings)));
      Assertions.assertEquals(Map.of("nested", Map.of("value", value)), new Yaml().load(WriterRegressionTest.yaml(Map.of("nested", settings))));
      Assertions.assertEquals(List.of(Map.of("value", value), "next"), new Yaml().load(WriterRegressionTest.yaml(List.of(settings, "next"))));
    }
  }

  @Test
  void jsonIgnoresYamlBlockStyleAnnotations() throws IOException {
    EndSettings settings = new EndSettings();
    settings.value = "C:\\temp\n";
    String output = WriterRegressionTest.json(settings);
    try (JsonParser parser = new JsonFactory().createParser(output)) {
      Assertions.assertEquals(JsonToken.START_OBJECT, parser.nextToken());
      Assertions.assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
      Assertions.assertEquals(JsonToken.VALUE_STRING, parser.nextToken());
      Assertions.assertEquals(settings.value, parser.getText());
      Assertions.assertEquals(JsonToken.END_OBJECT, parser.nextToken());
    }
  }

  @Test
  void preservedBlockStyleRoundTripsThroughSerializableSave() throws IOException {
    for (String marker : List.of("|-", "|", "|+", ">-", ">", ">+")) {
      PreservedSettings settings = new PreservedSettings();
      settings.load(new BufferedReader(new StringReader("value: " + marker + "\n  C:\\temp\tfile\n\n")));
      String expected = settings.value;
      StringWriter output = new StringWriter();
      try (BufferedWriter buffer = new BufferedWriter(output)) {
        settings.save(buffer);
      }
      Assertions.assertEquals(expected, ((Map<?, ?>) new Yaml().load(output.toString())).get("value"), output.toString());
      PreservedSettings reloaded = new PreservedSettings();
      reloaded.load(new BufferedReader(new StringReader(output.toString())));
      Assertions.assertEquals(expected, reloaded.value, output.toString());
    }
  }

  private static Object parseStyled(String style, String value, String separator) throws Exception {
    Field owner = Styles.class.getDeclaredField(style);
    StringWriter output = new StringWriter();
    try (BufferedWriter buffer = new BufferedWriter(output)) {
      YamlWriter writer = new YamlWriter(new SerializerConfig.Builder().setLineSeparator(separator).build(), buffer);
      writer.writeBeginMap();
      writer.writeMapEntry(owner, "value", value, true, owner.getAnnotationsByType(Comment.class));
      writer.writeMapEntry("next", "kept", false, null);
      writer.writeEndMap();
      writer.writeLine();
    }
    Map<?, ?> parsed = new Yaml().load(output.toString());
    Assertions.assertEquals("kept", parsed.get("next"), output.toString());
    return parsed.get("value");
  }

  private static String json(Object value) throws IOException {
    return WriterRegressionTest.write(value, true);
  }

  private static String yaml(Object value) throws IOException {
    return WriterRegressionTest.write(value, false);
  }

  private static String write(Object value, boolean json) throws IOException {
    StringWriter output = new StringWriter();
    try (BufferedWriter buffer = new BufferedWriter(output)) {
      SerializerConfig config = new SerializerConfig.Builder().setLineSeparator("\n").build();
      AbstractWriter writer = json ? new JsonWriter(buffer, config) : new YamlWriter(config, buffer);
      writer.writeNode(value, null);
    }
    return output.toString();
  }

  private enum Example {
    RED
  }

  private static class EndSettings {

    @YamlStringStyle(YamlWriter.StringStyle.MULTILINE_LITERAL_AUTO_KEPT)
    private String value;
  }

  private static class PreservedSettings extends YamlSerializable {

    @Comment(value = @CommentValue("hint"), at = Comment.At.SAME_LINE)
    private String value;
  }

  private static class Styles {

    @YamlStringStyle(YamlWriter.StringStyle.SINGLE_QUOTED)
    private String single;
    @Comment(value = @CommentValue("hint"), at = Comment.At.SAME_LINE)
    @YamlStringStyle(YamlWriter.StringStyle.MULTILINE_LITERAL_AUTO_CLIPPED)
    private String literal;
    @Comment(value = @CommentValue("hint"), at = Comment.At.SAME_LINE)
    @YamlStringStyle(YamlWriter.StringStyle.MULTILINE_FOLDED_AUTO_CLIPPED)
    private String folded;
    @YamlStringStyle(YamlWriter.StringStyle.MULTILINE_LITERAL_AUTO_KEPT)
    private String literalKept;
    @YamlStringStyle(YamlWriter.StringStyle.MULTILINE_FOLDED_AUTO_KEPT)
    private String foldedKept;
    @YamlStringStyle(YamlWriter.StringStyle.MULTILINE_LITERAL_STRIPPED)
    private String literalStripped;
    @YamlStringStyle(YamlWriter.StringStyle.MULTILINE_FOLDED_STRIPPED)
    private String foldedStripped;
  }
}
