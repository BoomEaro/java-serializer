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
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.language.reader.YamlReader;
import net.elytrium.serializer.language.writer.YamlWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SerializerChainTest {

  @Test
  void readsGenericSameTypeSerializerOnce() {
    SerializerConfig config = SerializerChainTest.config(new BoundedListSerializer());
    ListSettings settings = new ListSettings();
    new YamlReader(new BufferedReader(new StringReader("values: [value]\n")), config)
        .readSerializableObject(settings, ListSettings.class);

    Assertions.assertEquals(Arrays.asList("value", "decoded"), settings.values);
  }

  @Test
  void writesGenericSameTypeSerializerOnce() throws IOException {
    SerializerConfig config = SerializerChainTest.config(new BoundedListSerializer());

    Assertions.assertEquals("- \"value\"\n- \"encoded\"", SerializerChainTest.write(Collections.singletonList("value"), config));
  }

  @Test
  void readsSameTypeStringSerializer() {
    SerializerConfig config = SerializerChainTest.config(new StringSerializer());
    StringSettings settings = new StringSettings();
    new YamlReader(new BufferedReader(new StringReader("value: ' value '\n")), config)
        .readSerializableObject(settings, StringSettings.class);

    Assertions.assertEquals("value", settings.value);
  }

  @Test
  void writesSameTypeStringSerializer() throws IOException {
    SerializerConfig config = SerializerChainTest.config(new StringSerializer());

    Assertions.assertEquals("\"value\"", SerializerChainTest.write(" value ", config));
  }

  @Test
  void preservesChangingTypeSerializer() throws IOException {
    SerializerConfig config = SerializerChainTest.config(new ValueSerializer());
    ValueSettings settings = new ValueSettings();
    new YamlReader(new BufferedReader(new StringReader("value: stored\n")), config)
        .readSerializableObject(settings, ValueSettings.class);

    Assertions.assertEquals("stored", settings.value.text);
    Assertions.assertEquals("\"stored\"", SerializerChainTest.write(settings.value, config));
  }

  private static SerializerConfig config(ClassSerializer<?, ?> serializer) {
    return new SerializerConfig.Builder().registerSerializer(serializer).setLineSeparator("\n").build();
  }

  private static String write(Object value, SerializerConfig config) throws IOException {
    StringWriter output = new StringWriter();
    try (BufferedWriter writer = new BufferedWriter(output)) {
      new YamlWriter(config, writer).writeNode(value, null);
    }

    return output.toString();
  }

  private static class ListSettings {

    private List<String> values = Collections.emptyList();
  }

  private static class StringSettings {

    private String value = "default";
  }

  private static class ValueSettings {

    private Value value = new Value("default");
  }

  private static class Value {

    private final String text;

    private Value(String text) {
      this.text = text;
    }
  }

  private static class BoundedListSerializer extends ClassSerializer<List<String>, List<String>> {

    private int typeChecks;

    @Override
    public Type getToType() {
      // Bound both the reader's pipeline construction and the writer's conversion loop.
      if (++this.typeChecks > 16) {
        throw new AssertionError("Same-type serializer pipeline did not terminate");
      }

      return super.getToType();
    }

    @Override
    public List<String> serialize(List<String> from) {
      List<String> result = new ArrayList<>(from);
      result.add("encoded");
      return result;
    }

    @Override
    public List<String> deserialize(List<String> from) {
      List<String> result = new ArrayList<>(from);
      result.add("decoded");
      return result;
    }
  }

  private static class StringSerializer extends ClassSerializer<String, String> {

    @Override
    public String serialize(String from) {
      return from.trim();
    }

    @Override
    public String deserialize(String from) {
      return from.trim();
    }
  }

  private static class ValueSerializer extends ClassSerializer<Value, String> {

    @Override
    public String serialize(Value from) {
      return from.text;
    }

    @Override
    public Value deserialize(String from) {
      return new Value(from);
    }
  }
}
