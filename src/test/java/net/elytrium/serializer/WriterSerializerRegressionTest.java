package net.elytrium.serializer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.elytrium.serializer.annotations.Serializer;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.language.writer.YamlWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class WriterSerializerRegressionTest {

  @Test
  void fieldsAndContainerValuesUseSameTypeSerializerOnce() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new SuffixSerializer()).build();
    Assertions.assertEquals(Map.of("value", "x!", "values", List.of("x!"), "map", Map.of("key!", "x!")),
        new Yaml().load(WriterSerializerRegressionTest.write(new Settings(), config)));
  }

  @Test
  void annotatedRegisteredSameTypeSerializerRunsOnce() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new SuffixSerializer()).build();
    Assertions.assertEquals(Map.of("value", "x!"), new Yaml().load(WriterSerializerRegressionTest.write(new AnnotatedSettings(), config)));
  }

  @Test
  void annotatedSerializerContinuesThroughRegisteredChangingTypeStage() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new FirstSerializer()).registerSerializer(new SecondToStringSerializer()).build();
    Assertions.assertEquals(Map.of("value", "encoded"), new Yaml().load(WriterSerializerRegressionTest.write(new AnnotatedChainSettings(), config)));
  }

  @Test
  void nullSerializerOutputIsWrittenAsNull() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new NullSerializer()).build();
    Assertions.assertNull(new Yaml().load(WriterSerializerRegressionTest.write("x", config)));
    Map<?, ?> output = new Yaml().load(WriterSerializerRegressionTest.write(new Settings(), config));
    Assertions.assertNull(output.get("value"));
    Assertions.assertNull(((List<?>) output.get("values")).getFirst());
    Assertions.assertTrue(((Map<?, ?>) output.get("map")).containsKey(null));
    Assertions.assertNull(((Map<?, ?>) output.get("map")).get(null));
  }

  @Test
  void genericChangingSameRawSerializerTerminatesAfterOneConversion() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new GenericSerializer()).build();
    Assertions.assertEquals(List.of("1"), new Yaml().load(WriterSerializerRegressionTest.write(List.of(1), config)));
  }

  @Test
  void serializerCyclesFailBeforeRepeatingAStage() {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new FirstSerializer()).registerSerializer(new SecondSerializer()).build();
    Assertions.assertThrows(IllegalStateException.class, () -> WriterSerializerRegressionTest.write(new First(), config));
  }

  @Test
  void objectSerializerStopsWhenItsStringOutputInheritsTheRegistration() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new ObjectSerializer()).build();
    Assertions.assertEquals("encoded", new Yaml().load(WriterSerializerRegressionTest.write(new Object(), config)));
  }

  @Test
  void collectionSerializerStopsWhenItsListOutputInheritsTheRegistration() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new CollectionSerializer()).build();
    Assertions.assertEquals(List.of("encoded"), new Yaml().load(WriterSerializerRegressionTest.write(List.of("original"), config)));
  }

  @Test
  void inheritedIntermediateSerializerTypesStillDetectCycles() {
    SerializerConfig config = new SerializerConfig.Builder()
        .registerSerializer(new ClassSerializer<>(First.class, SecondChild.class) {
          @Override
          public SecondChild serialize(First from) {
            return new SecondChild();
          }
        })
        .registerSerializer(new ClassSerializer<>(Second.class, String.class) {
          @Override
          public String serialize(Second from) {
            return "wire";
          }
        })
        .registerSerializer(new ClassSerializer<>(String.class, Second.class) {
          @Override
          public Second serialize(String from) {
            return new Second();
          }
        }).build();
    Assertions.assertThrows(IllegalStateException.class, () -> WriterSerializerRegressionTest.write(new First(), config));
  }

  private static String write(Object value, SerializerConfig config) throws IOException {
    StringWriter output = new StringWriter();
    try (BufferedWriter buffer = new BufferedWriter(output)) {
      new YamlWriter(config, buffer).writeNode(value, null);
    }
    return output.toString();
  }

  private static class Settings {
    private String value = "x";
    private List<String> values = List.of("x");
    private Map<String, String> map = Map.of("key", "x");
  }

  private static class AnnotatedSettings {

    @Serializer(SuffixSerializer.class)
    private String value = "x";
  }

  private static class AnnotatedChainSettings {

    @Serializer(FirstSerializer.class)
    private First value = new First();
  }

  private static class SuffixSerializer extends ClassSerializer<String, String> {
    @Override
    public String serialize(String from) {
      return from + "!";
    }
  }

  private static class NullSerializer extends ClassSerializer<String, String> {
    @Override
    public String serialize(String from) {
      return null;
    }
  }

  private static class GenericSerializer extends ClassSerializer<List<Integer>, List<String>> {
    private int calls;

    @Override
    public List<String> serialize(List<Integer> from) {
      if (++this.calls > 1) {
        throw new AssertionError("Generic serializer was repeated");
      }
      return from.stream().map(String::valueOf).toList();
    }
  }

  private static class First {
  }

  private static class Second {
  }

  private static class SecondChild extends Second {
  }

  private static class FirstSerializer extends ClassSerializer<First, Second> {
    private int calls;

    @Override
    public Second serialize(First from) {
      if (++this.calls > 1) {
        throw new AssertionError("Cyclic serializer was repeated");
      }
      return new Second();
    }
  }

  private static class SecondSerializer extends ClassSerializer<Second, First> {
    @Override
    public First serialize(Second from) {
      return new First();
    }
  }

  private static class SecondToStringSerializer extends ClassSerializer<Second, String> {

    @Override
    public String serialize(Second from) {
      return "encoded";
    }
  }

  private static class ObjectSerializer extends ClassSerializer<Object, String> {

    @Override
    public String serialize(Object from) {
      return "encoded";
    }
  }

  private static class CollectionSerializer extends ClassSerializer<Collection<String>, List<String>> {

    @Override
    public List<String> serialize(Collection<String> from) {
      return List.of("encoded");
    }
  }
}
