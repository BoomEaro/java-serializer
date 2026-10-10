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
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.elytrium.serializer.annotations.Final;
import net.elytrium.serializer.annotations.RegisterPlaceholders;
import net.elytrium.serializer.language.reader.JsonReader;
import net.elytrium.serializer.language.reader.YamlReader;
import net.elytrium.serializer.placeholders.Placeholders;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReaderAuditRegressionTest {

  @Test
  void preservesBlankLinesAndSiblingsInBlockScalars() {
    for (String newline : List.of("\n", "\r\n")) {
      for (String blank : List.of("", "  ")) {
        for (String marker : List.of("|", ">")) {
          String input = ("value: " + marker + "\n  first\n" + blank + "\n  second\nnext: kept\n").replace("\n", newline);
          Settings settings = read(input);
          Assertions.assertEquals(marker.equals("|") ? "first\n\nsecond\n" : "first\nsecond\n", settings.value, input);
          Assertions.assertEquals("kept", settings.next, input);
        }
      }
    }
  }

  @Test
  void infersBlockIndentAfterLeadingBlankLines() {
    for (String newline : List.of("\n", "\r\n")) {
      for (String marker : List.of("|", ">")) {
        Settings settings = read(("value: " + marker + "\n\n  first\nnext: kept\n").replace("\n", newline));
        Assertions.assertEquals("\nfirst\n", settings.value);
        Assertions.assertEquals("kept", settings.next);
      }
    }
  }

  @Test
  void skipsBlankBlockScalarsWithoutLosingSiblings() {
    for (String field : List.of("unknown", "fixed")) {
      for (String newline : List.of("\n", "\r\n")) {
        Settings settings = read((field + ": |\n\n  first\n\n  second\nnext: kept\n").replace("\n", newline));
        Assertions.assertEquals("kept", settings.next);
        Assertions.assertEquals("default", settings.fixed);
      }
    }
  }

  @Test
  void foldsMoreIndentedLinesWithoutDiscardingLineBreaks() {
    Settings settings = read("value: >\n  first\n    indented\n  third\nnext: kept\n");
    Assertions.assertEquals("first\n  indented\nthird\n", settings.value);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void acceptsBlockHeaderCommentsAndEitherIndicatorOrder() {
    for (String header : List.of("| # comment", "|2- # comment", "|-2 # comment", "|2+", "|+2")) {
      Settings settings = read("value: " + header + "\n  text\nnext: kept\n");
      Assertions.assertEquals(header.contains("-") ? "text" : "text\n", settings.value, header);
      Assertions.assertEquals("kept", settings.next, header);
    }
  }

  @Test
  void readsRootFlowMapsInYamlAndJson() {
    Assertions.assertEquals(Map.of("key", "value"), reader("{key: value}").readMap(Object.class, Object.class));
    Assertions.assertEquals(Map.of("key", "value"), new JsonReader(input("{\"key\": \"value\"}")).readMap(Object.class, Object.class));
  }

  @Test
  void preservesQuotedNumericKeysWhileInferringPlainNumericKeys() {
    Assertions.assertEquals(Map.of("123", "one", 123L, "two"), reader("{'123': one, 123: two}").readMap(Object.class, Object.class));
    Assertions.assertEquals(Map.of("123", "one"), new JsonReader(input("{\"123\": \"one\"}")).readMap(Object.class, Object.class));
    Assertions.assertEquals(Map.of(123, "one"), reader("{'123': one}").readMap(Integer.class, Object.class));
  }

  @Test
  void readsAndSkipsFlowMapsInsideBlockLists() {
    for (String indent : List.of("", "  ")) {
      Settings settings = read("list:\n" + indent + "- {key: value}\nnext: kept\n");
      Assertions.assertEquals(List.of(Map.of("key", "value")), settings.list);
      Assertions.assertEquals("kept", settings.next);
      Assertions.assertEquals("kept", read("unknown:\n" + indent + "- {key: value}\nnext: kept\n").next);
    }
  }

  @Test
  void emptyValuesDoNotConsumeSiblings() {
    for (String field : List.of("any", "map", "list", "child", "unknown")) {
      Settings settings = read(field + ":\nnext: kept\n");
      Assertions.assertEquals("kept", settings.next, field);
      if (field.equals("any")) {
        Assertions.assertNull(settings.any);
      } else if (field.equals("map")) {
        Assertions.assertNull(settings.map);
      } else if (field.equals("list")) {
        Assertions.assertNull(settings.list);
      } else if (field.equals("child")) {
        Assertions.assertNull(settings.child);
      }
    }
  }

  @Test
  void acceptsEmptyNullableValuesAtEndOfInput() {
    for (String field : List.of("any", "map", "list", "child")) {
      Settings settings = read(field + ":");
      if (field.equals("any")) {
        Assertions.assertNull(settings.any);
      } else if (field.equals("map")) {
        Assertions.assertNull(settings.map);
      } else if (field.equals("list")) {
        Assertions.assertNull(settings.list);
      } else {
        Assertions.assertNull(settings.child);
      }
    }
  }

  @Test
  void readsNullContainersBeforeComments() {
    for (String field : List.of("map", "list", "child", "unknown", "fixed-map")) {
      Settings settings = read(field + ": null # comment\nnext: kept\n");
      Assertions.assertEquals("kept", settings.next, field);
      if (field.equals("map")) {
        Assertions.assertNull(settings.map);
      } else if (field.equals("list")) {
        Assertions.assertNull(settings.list);
      } else if (field.equals("child")) {
        Assertions.assertNull(settings.child);
      }
    }
  }

  @Test
  void acceptsCommentOnlyDocumentsAndFinalComments() {
    Assertions.assertEquals("default", read("# comment\n# another\n").next);
    Assertions.assertEquals("kept", read("next: kept\n# final comment\n").next);
    Assertions.assertEquals("kept", read("{next: kept}\n# final comment\n").next);
  }

  @Test
  void rejectsTruncatedFlowObjects() {
    for (String text : List.of("{value: first", "child: {value: first", "child: {value: one\nnext: kept\n")) {
      Assertions.assertThrows(IllegalStateException.class, () -> read(text), text);
    }
  }

  @Test
  void rejectsMissingFlowObjectCommas() {
    Assertions.assertThrows(IllegalStateException.class, () -> read("child: {value: \"one\"\nnext: two}\nnext: kept\n"));
  }

  @Test
  void rejectsTrailingContentAfterRootFlowObjects() {
    for (String text : List.of("{value: one}\nnext: kept\n", "{} garbage", "{value: one} {}")) {
      Assertions.assertThrows(IllegalStateException.class, () -> read(text), text);
    }
  }

  @Test
  void rejectsEmbeddedNulInsteadOfTreatingItAsEof() {
    Assertions.assertThrows(IllegalStateException.class, () -> read("value: first\n\u0000next: kept\n"));
  }

  @Test
  void resolvesPlainBooleansInGenericValues() {
    Settings settings = read("any: true\nmap: {yes: true, no: false, text: 'true'}\nlist: [true, false, 'false']\n");
    Assertions.assertEquals(Boolean.TRUE, settings.any);
    Assertions.assertEquals(Map.of("yes", true, "no", false, "text", "true"), settings.map);
    Assertions.assertEquals(List.of(true, false, "false"), settings.list);
  }

  @Test
  void readsDashPrefixedStringsWithoutMistakingThemForLists() {
    Settings settings = read("any: -hello\nlist: [-hello, -12, -3.5]\nunknown: -hello\nnext: kept\n");
    Assertions.assertEquals("-hello", settings.any);
    Assertions.assertEquals(Arrays.asList("-hello", -12L, -3.5), settings.list);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void distinguishesIndentedDashPrefixedScalarsAndMappingKeysFromLists() {
    Settings scalar = read("any:\n  -hello\nnext: kept\n");
    Assertions.assertEquals("-hello", scalar.any);
    Assertions.assertEquals("kept", scalar.next);
    Settings mapping = read("any:\n  -hello: world\nnext: kept\n");
    Assertions.assertEquals(Map.of("-hello", "world"), mapping.any);
    Assertions.assertEquals("kept", mapping.next);
  }

  @Test
  void readsNullableBooleans() {
    Settings settings = read("enabled: null\nnext: kept\n");
    Assertions.assertNull(settings.enabled);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void acceptsJsonEscapedSlashes() {
    Settings settings = new Settings();
    new JsonReader(input("{\"value\": \"https:\\/\\/example.com\\/path\", \"next\": \"kept\"}"))
        .readSerializableObject(settings, Settings.class);
    Assertions.assertEquals("https://example.com/path", settings.value);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void preservesNullPrefixedPojoKeysAndPlainValues() {
    Settings settings = read("child:\n  next: nested\nany: nothing\nnext: kept\n");
    Assertions.assertEquals("nested", settings.child.next);
    Assertions.assertEquals("nothing", settings.any);
    Assertions.assertEquals("kept", settings.next);
    Assertions.assertEquals(Map.of("next", "kept"), reader("next: kept\n").readMap(Object.class, Object.class));
  }

  @Test
  void preservesNullPrefixedKeysInBlockListObjects() {
    Settings settings = read("children:\n- next: nested\nlist:\n- next: value\nnext: kept\n");
    Assertions.assertEquals("nested", settings.children.get(0).next);
    Assertions.assertEquals(List.of(Map.of("next", "value")), settings.list);
    Assertions.assertEquals("kept", settings.next);
  }

  @Test
  void preservesTabsAtStartOfBlockContent() {
    for (String header : List.of("|2", ">2")) {
      Settings settings = read("value: " + header + "\n  \ttext\nnext: kept\n");
      Assertions.assertEquals("\ttext\n", settings.value, header);
      Assertions.assertEquals("kept", settings.next, header);
    }
  }

  @Test
  void preservesSpacesBeyondExplicitBlockIndent() {
    for (String newline : List.of("\n", "\r\n")) {
      Settings settings = read("value: |2\n    \n  text\n    \nnext: kept\n".replace("\n", newline));
      Assertions.assertEquals("  \ntext\n  \n", settings.value);
      Assertions.assertEquals("kept", settings.next);
    }
  }

  @Test
  void registersPlaceholdersForUnmodifiedFinalFields() {
    FinalPlaceholderSettings settings = new FinalPlaceholderSettings();
    reader("{}").readSerializableObject(settings, FinalPlaceholderSettings.class);
    Assertions.assertEquals("kept", Placeholders.replace(settings.finalValue, "kept"));
    Assertions.assertEquals("kept", Placeholders.replace(settings.annotatedValue, "kept"));
  }

  private static BufferedReader input(String value) {
    return new BufferedReader(new StringReader(value));
  }

  private static YamlReader reader(String value) {
    return new YamlReader(input(value), new SerializerConfig.Builder().setLogMissingFields(false).build()) {

      private int remainingReads = 100000;

      @Override
      public char readRaw() {
        if (--this.remainingReads < 0) {
          throw new AssertionError("Parser did not finish: " + value);
        }
        return super.readRaw();
      }
    };
  }

  private static Settings read(String value) {
    Settings settings = new Settings();
    reader(value).readSerializableObject(settings, Settings.class);
    return settings;
  }

  private static class Settings {

    private String value;
    private String next = "default";
    private Object any;
    private List<Object> list;
    private Map<String, Object> map;
    private Child child = new Child();
    private List<Child> children;
    private Boolean enabled = true;
    private final String fixed = "default";
    private final Map<String, Object> fixedMap = Map.of();
  }

  private static class Child {

    private String value;
    private String next;
  }

  private static class FinalPlaceholderSettings {

    @RegisterPlaceholders("name")
    private final String finalValue = new String("{name}");

    @Final
    @RegisterPlaceholders("name")
    private String annotatedValue = new String("{name}");
  }
}
