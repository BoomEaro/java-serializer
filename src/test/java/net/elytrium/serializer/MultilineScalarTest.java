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
import net.elytrium.serializer.language.reader.YamlReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MultilineScalarTest {

  @Test
  void readsLiteralScalarWithoutFinalNewline() {
    for (String marker : Arrays.asList("|", "|-", "|+", "|2", "|-2", "|+2")) {
      String input = "value: " + marker + "\n  first\n  second";
      Assertions.assertEquals("first\nsecond", MultilineScalarTest.read(input).value, input);
    }
  }

  @Test
  void readsFoldedScalarWithoutFinalNewline() {
    for (String marker : Arrays.asList(">", ">-", ">+", ">2", ">-2", ">+2")) {
      String input = "value: " + marker + "\n  first\n  second";
      Assertions.assertEquals("first second", MultilineScalarTest.read(input).value, input);
    }
  }

  @Test
  void preservesTrailingSpacesWithoutFinalNewline() {
    for (String marker : Arrays.asList("|", "|-", "|+", ">", ">-", ">+")) {
      String input = "value: " + marker + "\n  text   ";
      Assertions.assertEquals("text   ", MultilineScalarTest.read(input).value, input);
    }
  }

  @Test
  void finishesAfterTrailingIndentationAtEndOfFile() {
    for (String marker : Arrays.asList("|", ">")) {
      Assertions.assertEquals("text\n", MultilineScalarTest.read("value: " + marker + "\n  text\n  ").value, marker);
      Assertions.assertEquals("text", MultilineScalarTest.read("value: " + marker + "-\n  text\n  ").value, marker);
      Assertions.assertEquals("text\n", MultilineScalarTest.read("value: " + marker + "+\n  text\n  ").value, marker);
    }
  }

  @Test
  void honorsChompingWithFinalNewlines() {
    for (String marker : Arrays.asList("|", ">")) {
      Assertions.assertEquals("text\n", MultilineScalarTest.read("value: " + marker + "\n  text\n").value, marker);
      Assertions.assertEquals("text", MultilineScalarTest.read("value: " + marker + "-\n  text\n  \n").value, marker);
      Assertions.assertEquals("text\n", MultilineScalarTest.read("value: " + marker + "\n  text\n  \n").value, marker);
      Assertions.assertEquals("text\n\n", MultilineScalarTest.read("value: " + marker + "+\n  text\n  \n").value, marker);
    }
  }

  @Test
  void readsSiblingAfterMultilineScalar() {
    Settings literal = MultilineScalarTest.read("value: |\n  first\n  second\nnext: kept\n");
    Assertions.assertEquals("first\nsecond\n", literal.value);
    Assertions.assertEquals("kept", literal.next);

    Settings folded = MultilineScalarTest.read("value: >\n  first\n  second\nnext: kept\n");
    Assertions.assertEquals("first second\n", folded.value);
    Assertions.assertEquals("kept", folded.next);
  }

  @Test
  void skipsUnknownAndFinalScalarsAtEndOfFile() {
    for (String field : Arrays.asList("unknown", "fixed")) {
      for (String marker : Arrays.asList("|", "|-", "|+", ">", ">-", ">+")) {
        for (String ending : Arrays.asList("", "   ", "\n", "\n  ", "\n  \n")) {
          String input = "next: kept\n" + field + ": " + marker + "\n  text" + ending;
          Settings settings = MultilineScalarTest.read(input);
          Assertions.assertEquals("kept", settings.next, input);
          Assertions.assertEquals("default", settings.fixed, input);
        }
      }
    }
  }

  @Test
  void readsSiblingAfterSkippedMultilineScalar() {
    for (String field : Arrays.asList("unknown", "fixed")) {
      for (String marker : Arrays.asList("|", "|-", "|+", ">", ">-", ">+")) {
        String input = field + ": " + marker + "\n  first\n  second\nnext: kept\n";
        Settings settings = MultilineScalarTest.read(input);
        Assertions.assertEquals("kept", settings.next, input);
        Assertions.assertEquals("default", settings.fixed, input);
      }
    }
  }

  private static Settings read(String input) {
    YamlReader reader = new YamlReader(new BufferedReader(new StringReader(input)), new SerializerConfig.Builder().setLogMissingFields(false).build()) {

      private int remainingReads = 10000;

      @Override
      public char readRaw() {
        // Bound parser operations so an EOF regression cannot hang the test process.
        if (--this.remainingReads < 0) {
          throw new AssertionError("Parser did not finish reading: " + input);
        }

        return super.readRaw();
      }
    };
    Settings settings = new Settings();
    reader.readSerializableObject(settings, Settings.class);
    return settings;
  }

  private static class Settings {

    private String value;
    private String next = "default";
    private final String fixed = "default";
  }
}
