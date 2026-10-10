/*
 * Copyright (C) 2023 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.serializer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.elytrium.serializer.language.object.YamlSerializable;
import net.elytrium.serializer.language.reader.JsonReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuotedValueTest {

  @Test
  void rejectsMissingQuoteWithoutRewritingConfig(@TempDir Path directory) throws IOException {
    String input = "test-section:\n"
        + "  # Some description for this test value1\n"
        + "  value-test: \"\n"
        + "  # Some description for this test value2\n"
        + "  value-test-2: \"test2\"\n";
    Path path = directory.resolve("config.yml");
    Files.writeString(path, input);
    Settings settings = new Settings();

    Assertions.assertThrows(IllegalStateException.class, () -> settings.reload(path));
    Assertions.assertEquals(input, Files.readString(path));
    Assertions.assertEquals("original", settings.testSection.valueTest);
    Assertions.assertEquals("test2", settings.testSection.valueTest2);
  }

  @Test
  void rejectsTextAfterQuotedStrings() {
    for (String input : Arrays.asList("value: \"text\"tail\n", "value: 'text'tail\n",
        "value: \"text\" tail\n", "value: 'text' tail\n")) {
      Assertions.assertThrows(IllegalStateException.class, () -> QuotedValueTest.read(input), input);
    }
  }

  @Test
  void rejectsTextAfterQuotedCharacters() {
    for (String input : Arrays.asList("character: \"x\"tail\n", "character: 'x' tail\n")) {
      Assertions.assertThrows(IllegalStateException.class, () -> QuotedValueTest.read(input), input);
    }
  }

  @Test
  void rejectsTextAfterSkippedQuotedValues() {
    for (String input : Arrays.asList("unknown: \"text\"tail\n", "unknown: 'text' tail\n",
        "fixed: \"text\"tail\n", "fixed: 'text' tail\n")) {
      Assertions.assertThrows(IllegalStateException.class, () -> QuotedValueTest.read(input), input);
    }
  }

  @Test
  void rejectsFlowDelimitersAfterBlockValues() {
    for (String input : Arrays.asList("value: \"text\",\n", "value: 'text']\n", "value: \"text\"}\n")) {
      Assertions.assertThrows(IllegalStateException.class, () -> QuotedValueTest.read(input), input);
    }
  }

  @Test
  void preservesMultilineQuotedStrings() {
    for (String quote : Arrays.asList("\"", "'")) {
      Settings settings = QuotedValueTest.read("value: " + quote + "first line\n"
          + "# literal text\n"
          + "tail: also literal" + quote + "\ntail: kept\n");
      Assertions.assertEquals("first line # literal text tail: also literal", settings.value);
      Assertions.assertEquals("kept", settings.tail);
    }
  }

  @Test
  void preservesEmptyLinesInsideQuotedStrings() {
    for (String quote : Arrays.asList("\"", "'")) {
      Settings settings = QuotedValueTest.read("value: " + quote + "first\n\nsecond" + quote + "\ntail: kept\n");
      Assertions.assertEquals("first\nsecond", settings.value);
      Assertions.assertEquals("kept", settings.tail);
    }
  }

  @Test
  void readsQuotedValuesBeforeLineEndOrEof() {
    for (String ending : Arrays.asList("", "  ", "\n", "\r\n", "\r")) {
      for (String quote : Arrays.asList("\"", "'")) {
        Settings settings = QuotedValueTest.read("value: " + quote + "text" + quote + ending);
        Assertions.assertEquals("text", settings.value);
      }
    }
  }

  @Test
  void readsEmptyQuotedValues() {
    for (String quote : Arrays.asList("\"", "'")) {
      Settings settings = QuotedValueTest.read("value: " + quote + quote + "\ntail: kept\n");
      Assertions.assertEquals("", settings.value);
      Assertions.assertEquals("kept", settings.tail);
    }
  }

  @Test
  void readsQuotedValuesBeforeComments() {
    Settings settings = QuotedValueTest.read("value: \"text\" # comment\ncharacter: 'x' # comment\ntail: kept\n");
    Assertions.assertEquals("text", settings.value);
    Assertions.assertEquals(Character.valueOf('x'), settings.character);
    Assertions.assertEquals("kept", settings.tail);
  }

  @Test
  void skipsQuotedValuesBeforeComments() {
    Settings settings = QuotedValueTest.read("unknown: \"text\" # comment\nfixed: 'text' # comment\ntail: kept\n");
    Assertions.assertEquals("original", settings.fixed);
    Assertions.assertEquals("kept", settings.tail);
  }

  @Test
  void readsQuotedValuesInFlowCollections() {
    Settings settings = QuotedValueTest.read("list: [\"first\", 'second' # comment\n]\n"
        + "map: {\"first\": \"one\", 'second': 'two'}\ntail: kept\n");
    Assertions.assertEquals(Arrays.asList("first", "second"), settings.list);
    Assertions.assertEquals(Map.of("first", "one", "second", "two"), settings.map);
    Assertions.assertEquals("kept", settings.tail);
  }

  @Test
  void readsQuotedValuesInBlockList() {
    Settings settings = QuotedValueTest.read("list:\n- \"first\"\n- 'second'\ntail: kept\n");
    Assertions.assertEquals(Arrays.asList("first", "second"), settings.list);
    Assertions.assertEquals("kept", settings.tail);
  }

  @Test
  void preservesQuotedKeysAndEscapedQuotes() {
    Settings settings = QuotedValueTest.read("\"value\": \"text \\\"quoted\\\"\"\n'character': '\"'\n\"tail\": 'it''s kept'\n");
    Assertions.assertEquals("text \"quoted\"", settings.value);
    Assertions.assertEquals(Character.valueOf('"'), settings.character);
    Assertions.assertEquals("it's kept", settings.tail);
  }

  @Test
  void readsJsonCommentsAfterQuotedValues() {
    JsonReader reader = new JsonReader(new BufferedReader(new StringReader("{\"value\": \"text\" // comment\n,\"tail\": \"kept\"}")));
    Settings settings = new Settings();
    reader.readSerializableObject(settings, Settings.class);
    Assertions.assertEquals("text", settings.value);
    Assertions.assertEquals("kept", settings.tail);
  }

  @Test
  void rejectsTextAfterJsonQuotedValue() {
    JsonReader reader = new JsonReader(new BufferedReader(new StringReader("{\"value\": \"text\"tail}")));
    Assertions.assertThrows(IllegalStateException.class, () -> reader.readSerializableObject(new Settings(), Settings.class));
  }

  private static Settings read(String input) {
    Settings settings = new Settings();
    settings.load(new BufferedReader(new StringReader(input)));
    return settings;
  }

  private static class Settings extends YamlSerializable {

    private Section testSection = new Section();
    private String value = "original";
    private Character character;
    private final String fixed = "original";
    private List<String> list = Collections.emptyList();
    private Map<String, String> map = Collections.emptyMap();
    private String tail = "original";
  }

  private static class Section {

    private String valueTest = "original";
    private String valueTest2 = "test2";
  }
}
