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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.elytrium.serializer.language.reader.JsonReader;
import net.elytrium.serializer.language.reader.YamlReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FlowCollectionTest {

  @Test
  void readsEmptyFlowList() {
    Settings settings = FlowCollectionTest.read("list: []");
    Assertions.assertEquals(Collections.emptyList(), settings.list);
  }

  @Test
  void readsEmptyFlowListBeforeAnotherField() {
    Settings settings = FlowCollectionTest.read("list: []\nnext: kept\n");
    Assertions.assertEquals(Collections.emptyList(), settings.list);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsPlainFlowListBeforeAnotherField() {
    Settings settings = FlowCollectionTest.read("list: [value1, value2]\nnext: kept\n");
    Assertions.assertEquals(Arrays.asList("value1", "value2"), settings.list);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsQuotedFlowList() {
    Settings settings = FlowCollectionTest.read("list: [\"value1\", 'value2']\nnext: kept\n");
    Assertions.assertEquals(Arrays.asList("value1", "value2"), settings.list);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsNumericFlowList() {
    Settings settings = FlowCollectionTest.read("numbers: [12, 34]\n");
    Assertions.assertEquals(Arrays.asList(12, 34), settings.numbers);
  }

  @Test
  void readsFlowListAtEndOfInput() {
    Settings settings = FlowCollectionTest.read("list: [value1, value2]");
    Assertions.assertEquals(Arrays.asList("value1", "value2"), settings.list);
  }

  @Test
  void readsFlowListWithCommentsAndNewLines() {
    Settings settings = FlowCollectionTest.read("list: [ # comment\n  one, # another comment\n  two,\n]\nnext: kept\n");
    Assertions.assertEquals(Arrays.asList("one", "two"), settings.list);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsFlowListWithCommentBeforeSeparator() {
    Settings settings = FlowCollectionTest.read("list: [one # comment\n, two]\nnext: kept\n");
    Assertions.assertEquals(Arrays.asList("one", "two"), settings.list);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsFlowListsInsideBlockList() {
    Settings settings = FlowCollectionTest.read("value:\n- [one, two]\n- [three]\nnext: kept\n");
    Assertions.assertEquals(Arrays.asList(Arrays.asList("one", "two"), Collections.singletonList("three")), settings.value);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsFlowListWithNullsAndNegativeNumbers() {
    Settings settings = FlowCollectionTest.read("value: [null, -12, -3.5, null]\nnext: kept\n");
    Assertions.assertEquals(Arrays.asList(null, -12L, -3.5, null), settings.value);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsCharacterFlowList() {
    Settings settings = FlowCollectionTest.read("characters: [a, 'b', \"c\", null]\nnext: kept\n");
    Assertions.assertEquals(Arrays.asList('a', 'b', 'c', null), settings.characters);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsNestedFlowCollections() {
    Settings settings = FlowCollectionTest.read("value: [[], [one, two], {key: [three]}]\nnext: kept\n");
    Assertions.assertEquals(Arrays.asList(Collections.emptyList(), Arrays.asList("one", "two"),
        Collections.singletonMap("key", Collections.singletonList("three"))), settings.value);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsFlowMapContainingLists() {
    Settings settings = FlowCollectionTest.read("values: {first: [one], second: []}\nnext: kept\n");
    Assertions.assertEquals(Map.of("first", Collections.singletonList("one"), "second", Collections.emptyList()), settings.values);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void skipsUnknownFlowListBeforeAnotherField() {
    Settings settings = FlowCollectionTest.read("unknown: [value1, value2]\nnext: kept\n");
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void skipsNestedUnknownFlowCollections() {
    Settings settings = FlowCollectionTest.read("unknown: [[], [one, two], {key: [three]}]\nnext: kept\n");
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void skipsSingleQuotedUnknownFlowList() {
    Settings settings = FlowCollectionTest.read("unknown: ['one', 'two']\nnext: kept\n");
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void skipsFinalFlowList() {
    Settings settings = FlowCollectionTest.read("fixed: [one, two]\nnext: kept\n");
    Assertions.assertEquals(Collections.singletonList("default"), settings.fixed);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void skipsUnknownFlowListWithNegativeNumbers() {
    Settings settings = FlowCollectionTest.read("unknown: [-12, -3.5]\nnext: kept\n");
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsJsonFlowList() {
    JsonReader reader = new JsonReader(FlowCollectionTest.boundedInput("{\"list\": [\"one\", \"two\"], \"next\": \"kept\"}"));
    Settings settings = new Settings();
    reader.readSerializableObject(settings, Settings.class);
    Assertions.assertEquals(Arrays.asList("one", "two"), settings.list);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsJsonObjectWithNumericAndBooleanFields() {
    JsonReader reader = new JsonReader(FlowCollectionTest.boundedInput("{\n\"count\": 12,\n\"enabled\": true,\n\"next\": \"kept\"\n}"));
    ScalarSettings settings = new ScalarSettings();
    reader.readSerializableObject(settings, ScalarSettings.class);
    Assertions.assertEquals(12L, settings.count);
    Assertions.assertTrue(settings.enabled);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void readsFlowObjectsInsideList() {
    Settings settings = FlowCollectionTest.read("objects: [{count: 12, enabled: true, next: kept}, {}]\nnext: kept, too\n");
    Assertions.assertEquals(2, settings.objects.size());
    Assertions.assertEquals(12L, settings.objects.get(0).count);
    Assertions.assertTrue(settings.objects.get(0).enabled);
    Assertions.assertEquals("kept", settings.objects.get(0).next);
    Assertions.assertEquals(0L, settings.objects.get(1).count);
    Assertions.assertEquals("kept, too", settings.next);
  }

  @Test
  void rejectsUnterminatedFlowList() {
    Assertions.assertThrows(IllegalStateException.class, () -> FlowCollectionTest.read("list: [value1, value2"));
  }

  @Test
  void rejectsUnterminatedUnknownFlowList() {
    Assertions.assertThrows(IllegalStateException.class, () -> FlowCollectionTest.read("unknown: [value1, value2"));
  }

  @Test
  void rejectsUnterminatedQuotedFlowElements() {
    for (String input : Arrays.asList("list: [\"value", "list: ['value", "characters: [\"x", "characters: ['x",
        "unknown: [\"value", "unknown: ['value")) {
      Assertions.assertThrows(IllegalStateException.class, () -> FlowCollectionTest.read(input), input);
    }
  }

  @Test
  void rejectsMissingFlowSeparator() {
    Assertions.assertThrows(IllegalStateException.class, () -> FlowCollectionTest.read("list: [\"one\" \"two\"]"));
  }

  @Test
  void rejectsMismatchedClosingBracket() {
    Assertions.assertThrows(IllegalStateException.class, () -> FlowCollectionTest.read("list: [one}"));
  }

  @Test
  void stillReadsBlockList() {
    Settings settings = FlowCollectionTest.read("list:\n- value1\n- value2\nnext: kept\n");
    Assertions.assertEquals(Arrays.asList("value1", "value2"), settings.list);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void keepsCommasAndBracketsInBlockValues() {
    Settings settings = FlowCollectionTest.read("list:\n- one, two\n- brackets[]{}\nnext: kept, too\n");
    Assertions.assertEquals(Arrays.asList("one, two", "brackets[]{}"), settings.list);
    Assertions.assertEquals("kept, too", settings.next);
  }

  private static Settings read(String input) {
    YamlReader reader = new YamlReader(FlowCollectionTest.boundedInput(input), new SerializerConfig.Builder().setLogMissingFields(false).build());
    Settings settings = new Settings();
    reader.readSerializableObject(settings, Settings.class);
    return settings;
  }

  private static BufferedReader boundedInput(String input) {
    // Bound the real parser's work so a regression cannot hang the test process.
    return new BufferedReader(new StringReader(input)) {

      private int remainingReads = 10000;

      @Override
      public int read(char[] buffer, int offset, int length) throws IOException {
        if (--this.remainingReads < 0) {
          throw new AssertionError("Parser did not finish reading: " + input);
        }

        return super.read(buffer, offset, length);
      }
    };
  }

  private static class Settings {

    private List<String> list = Collections.singletonList("default");
    private List<Integer> numbers = Collections.emptyList();
    private List<Character> characters = Collections.emptyList();
    private final List<String> fixed = Collections.singletonList("default");
    private Map<String, List<String>> values = Collections.emptyMap();
    private List<ScalarSettings> objects = Collections.emptyList();
    private Object value;
    private String next = "default";
  }

  private static class ScalarSettings {

    private long count;
    private boolean enabled;
    private String next;
  }
}
