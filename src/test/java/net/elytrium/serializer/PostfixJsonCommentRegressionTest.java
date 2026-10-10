package net.elytrium.serializer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.elytrium.serializer.annotations.Comment;
import net.elytrium.serializer.annotations.CommentValue;
import net.elytrium.serializer.language.object.JsonSerializable;
import net.elytrium.serializer.language.object.YamlSerializable;
import net.elytrium.serializer.language.reader.JsonReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class PostfixJsonCommentRegressionTest {

  @Test
  void adjacentScalarCommentsTerminateAtEndOfFile() throws Exception {
    for (String source : List.of("23// After number", "-23// : } ] # text", "2.5//decimal", "true//enabled", "false//disabled", "null//absent")) {
      Object expected = ((List<?>) json("[" + source + "\n]")).get(0);
      Object actual = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),
          () -> new JsonReader(input(source)).readByType(Object.class));
      if (expected instanceof Number number) {
        Assertions.assertEquals(number.doubleValue(), ((Number) actual).doubleValue(), source);
      } else {
        Assertions.assertEquals(expected, actual, source);
      }
    }
    Assertions.assertEquals(23, new JsonReader(input("23//EOF")).readByType(Integer.class));
    Assertions.assertEquals(true, new JsonReader(input("true//EOF")).readByType(Boolean.class));
    Assertions.assertNull(new JsonReader(input("null//EOF")).readByType(Integer.class));
  }

  @Test
  void adjacentFieldCommentsPreserveValuesAndSiblingsOnReload(@TempDir Path directory) throws Exception {
    for (String newline : List.of("\n", "\r\n")) {
      String source = ("{\"number\":23// After second: 999 ] } # ignored\n,\"enabled\":true//enabled\n"
          + ",\"optional\":null// absent\n,\"next\":\"kept\"}\n").replace("\n", newline);
      Path path = directory.resolve("comments.json");
      Files.writeString(path, source);
      Settings settings = new Settings();
      Assertions.assertEquals(LoadResult.SUCCESS, settings.reload(path));
      Assertions.assertEquals(23, settings.number);
      Assertions.assertTrue(settings.enabled);
      Assertions.assertNull(settings.optional);
      Assertions.assertEquals("kept", settings.next);
      Assertions.assertEquals(json(source), json(Files.readString(path)));
      Settings restored = new Settings();
      Assertions.assertEquals(LoadResult.SUCCESS, restored.load(path));
      Assertions.assertEquals(23, restored.number);
      Assertions.assertTrue(restored.enabled);
      Assertions.assertNull(restored.optional);
      Assertions.assertEquals("kept", restored.next);
    }
  }

  @Test
  void adjacentArrayCommentsDoNotConsumeCommasOrFollowingEntries(@TempDir Path directory) throws Exception {
    String source = "{\"numbers\":[23// : ] } ignored\n,24],\"flags\":[true//flag\n,false],"
        + "\"nullable\":[null//null\n,4],\"next\":\"kept\"}";
    Path path = directory.resolve("arrays.json");
    Files.writeString(path, source);
    ArraySettings settings = new ArraySettings();
    Assertions.assertEquals(LoadResult.SUCCESS, settings.reload(path));
    Assertions.assertEquals(List.of(23, 24), settings.numbers);
    Assertions.assertEquals(List.of(true, false), settings.flags);
    Assertions.assertEquals(2, settings.nullable.size());
    Assertions.assertNull(settings.nullable.get(0));
    Assertions.assertEquals(4, settings.nullable.get(1));
    Assertions.assertEquals("kept", settings.next);
    Assertions.assertEquals(json(source), json(Files.readString(path)));
  }

  @Test
  void jsonWriterSameLineCommentsRoundTripScalarFields(@TempDir Path directory) throws Exception {
    CommentedSettings original = new CommentedSettings();
    original.number = 23;
    original.enabled = true;
    original.optional = null;
    Path path = directory.resolve("written-comments.json");
    original.save(path);
    Map<?, ?> written = (Map<?, ?>) json(Files.readString(path));
    Assertions.assertEquals(23, written.get("number"));
    Assertions.assertEquals(true, written.get("enabled"));
    Assertions.assertNull(written.get("optional"));
    CommentedSettings restored = new CommentedSettings();
    Assertions.assertEquals(LoadResult.SUCCESS, restored.load(path));
    Assertions.assertEquals(23, restored.number);
    Assertions.assertTrue(restored.enabled);
    Assertions.assertNull(restored.optional);
  }

  @Test
  void yamlPlainSlashesAndAttachedHashesRemainLiteral(@TempDir Path directory) throws Exception {
    for (String text : List.of("https://example.test/a//b#anchor", "path/to/file", "23//still literal", "thing#literal")) {
      String source = "text: " + text + "\nnext: kept\n";
      Path path = directory.resolve("slashes.yml");
      Files.writeString(path, source);
      YamlSettings settings = new YamlSettings();
      Assertions.assertEquals(LoadResult.SUCCESS, settings.reload(path));
      Assertions.assertEquals(text, settings.text);
      Assertions.assertEquals("kept", settings.next);
      Map<String, Object> saved = new Yaml().load(Files.readString(path));
      Assertions.assertEquals(text, saved.get("text"));
    }
    YamlSettings settings = new YamlSettings();
    Assertions.assertTrue(settings.load(input("text: kept # separated comment\nnext: kept\n")));
    Assertions.assertEquals("kept", settings.text);
  }

  @Test
  void jsonQuotedSlashesAndCommentMarkersRemainLiteral(@TempDir Path directory) throws Exception {
    for (String text : List.of("https://example.test/a//b#anchor", "path/to/file", "23//still literal", "// literal : ] }")) {
      String source = "{\"text\":\"" + text + "\",\"next\":\"kept\"}";
      Path path = directory.resolve("quoted.json");
      Files.writeString(path, source);
      TextSettings settings = new TextSettings();
      Assertions.assertEquals(LoadResult.SUCCESS, settings.reload(path));
      Assertions.assertEquals(text, settings.text);
      Assertions.assertEquals("kept", settings.next);
      Assertions.assertEquals(json(source), json(Files.readString(path)));
    }
  }

  @Test
  void singleSlashAfterJsonNumberDoesNotBecomeComment() {
    Assertions.assertThrows(RuntimeException.class, () -> new JsonReader(input("23/not a comment")).readByType(Integer.class));
  }

  private static BufferedReader input(String source) {
    return new BufferedReader(new StringReader(source));
  }

  private static Object json(String source) throws IOException {
    JsonFactory factory = JsonFactory.builder().enable(JsonReadFeature.ALLOW_JAVA_COMMENTS).build();
    try (JsonParser parser = factory.createParser(source)) {
      parser.nextToken();
      Object value = readJson(parser);
      Assertions.assertNull(parser.nextToken());
      return value;
    }
  }

  private static Object readJson(JsonParser parser) throws IOException {
    if (parser.currentToken() == JsonToken.START_OBJECT) {
      Map<String, Object> result = new LinkedHashMap<>();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String key = parser.currentName();
        parser.nextToken();
        result.put(key, readJson(parser));
      }
      return result;
    } else if (parser.currentToken() == JsonToken.START_ARRAY) {
      List<Object> result = new ArrayList<>();
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        result.add(readJson(parser));
      }
      return result;
    }
    return switch (parser.currentToken()) {
      case VALUE_STRING -> parser.getText();
      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getNumberValue();
      case VALUE_TRUE -> true;
      case VALUE_FALSE -> false;
      case VALUE_NULL -> null;
      default -> throw new IllegalStateException("Unexpected JSON token " + parser.currentToken());
    };
  }

  private static class Settings extends JsonSerializable {

    private int number = 9;
    private boolean enabled;
    private Integer optional = 9;
    private String next = "original";
  }

  private static class ArraySettings extends JsonSerializable {

    private List<Integer> numbers = new ArrayList<>();
    private List<Boolean> flags = new ArrayList<>();
    private List<Integer> nullable = new ArrayList<>();
    private String next = "original";
  }

  private static class CommentedSettings extends JsonSerializable {

    @Comment(value = @CommentValue("after number"), at = Comment.At.SAME_LINE)
    private int number = 9;
    @Comment(value = @CommentValue("after boolean"), at = Comment.At.SAME_LINE)
    private boolean enabled;
    @Comment(value = @CommentValue("after null"), at = Comment.At.SAME_LINE)
    private Integer optional = 9;
  }

  private static class YamlSettings extends YamlSerializable {

    private String text = "original";
    private String next = "original";
  }

  private static class TextSettings extends JsonSerializable {

    private String text = "original";
    private String next = "original";
  }
}
