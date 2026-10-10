package net.elytrium.serializer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.function.Function;
import net.elytrium.serializer.annotations.Serializer;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.language.reader.AbstractReader;
import net.elytrium.serializer.language.reader.JsonReader;
import net.elytrium.serializer.language.reader.YamlReader;
import net.elytrium.serializer.language.writer.AbstractWriter;
import net.elytrium.serializer.language.writer.JsonWriter;
import net.elytrium.serializer.language.writer.YamlWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class TypeReauditRegressionTest {

  private static final SerializerConfig DEFAULT_CONFIG = new SerializerConfig.Builder().setLogMissingFields(false).build();

  @Test
  void annotatedConcreteConverterDecodesInterfaceField() throws IOException {
    for (boolean json : new boolean[] {false, true}) {
      InterfaceSettings result = new InterfaceSettings();
      TypeReauditRegressionTest.read(json ? "{\"value\": \"interface\"}" : "value: \"interface\"\n", result, DEFAULT_CONFIG, json);
      Assertions.assertEquals("interface", Assertions.assertInstanceOf(Concrete.class, result.value).text);
      String output = TypeReauditRegressionTest.write(result, DEFAULT_CONFIG, json);
      Assertions.assertEquals(Map.of("value", "interface"), new Yaml().load(output));
      InterfaceSettings reloaded = new InterfaceSettings();
      TypeReauditRegressionTest.read(output, reloaded, DEFAULT_CONFIG, json);
      Assertions.assertEquals("interface", Assertions.assertInstanceOf(Concrete.class, reloaded.value).text);
    }
  }

  @Test
  void annotatedConcreteConverterDecodesBaseField() throws IOException {
    for (boolean json : new boolean[] {false, true}) {
      BaseSettings result = new BaseSettings();
      TypeReauditRegressionTest.read(json ? "{\"value\": \"base\"}" : "value: \"base\"\n", result, DEFAULT_CONFIG, json);
      Assertions.assertEquals("base", Assertions.assertInstanceOf(Concrete.class, result.value).text);
      String output = TypeReauditRegressionTest.write(result, DEFAULT_CONFIG, json);
      Assertions.assertEquals(Map.of("value", "base"), new Yaml().load(output));
      BaseSettings reloaded = new BaseSettings();
      TypeReauditRegressionTest.read(output, reloaded, DEFAULT_CONFIG, json);
      Assertions.assertEquals("base", Assertions.assertInstanceOf(Concrete.class, reloaded.value).text);
    }
  }

  @Test
  void boxedRegisteredConvertersRoundTripAllPrimitiveFields() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().setLogMissingFields(false)
        .registerSerializer(TypeReauditRegressionTest.converter(Integer.class, Integer::valueOf))
        .registerSerializer(TypeReauditRegressionTest.converter(Long.class, Long::valueOf))
        .registerSerializer(TypeReauditRegressionTest.converter(Short.class, Short::valueOf))
        .registerSerializer(TypeReauditRegressionTest.converter(Byte.class, Byte::valueOf))
        .registerSerializer(TypeReauditRegressionTest.converter(Float.class, Float::valueOf))
        .registerSerializer(TypeReauditRegressionTest.converter(Double.class, Double::valueOf))
        .registerSerializer(TypeReauditRegressionTest.converter(Boolean.class, Boolean::valueOf))
        .registerSerializer(TypeReauditRegressionTest.converter(Character.class, text -> text.charAt(0)))
        .build();
    for (boolean json : new boolean[] {false, true}) {
      String output = TypeReauditRegressionTest.write(new PrimitiveSettings(), config, json);
      Assertions.assertEquals(Map.of("integer", "wire:7", "large", "wire:9", "small", "wire:3", "tiny", "wire:2",
          "decimal", "wire:1.5", "precise", "wire:2.25", "enabled", "wire:true", "marker", "wire:Q"), new Yaml().load(output));
      PrimitiveSettings result = new PrimitiveSettings();
      result.integer = 0;
      result.large = 0;
      result.small = 0;
      result.tiny = 0;
      result.decimal = 0;
      result.precise = 0;
      result.enabled = false;
      result.marker = '?';
      TypeReauditRegressionTest.read(output, result, config, json);
      Assertions.assertEquals(7, result.integer);
      Assertions.assertEquals(9L, result.large);
      Assertions.assertEquals((short) 3, result.small);
      Assertions.assertEquals((byte) 2, result.tiny);
      Assertions.assertEquals(1.5F, result.decimal);
      Assertions.assertEquals(2.25D, result.precise);
      Assertions.assertTrue(result.enabled);
      Assertions.assertEquals('Q', result.marker);
    }
  }

  @Test
  void rejectsUnrelatedAnnotatedConverterOutputType() {
    for (boolean json : new boolean[] {false, true}) {
      Assertions.assertThrows(IllegalStateException.class,
          () -> TypeReauditRegressionTest.read(json ? "{\"value\": \"wrong\"}" : "value: wrong\n",
              new UnrelatedSettings(), DEFAULT_CONFIG, json));
    }
  }

  @Test
  void rejectsActualBaseConverterOutputForConcreteField() {
    SerializerConfig config = new SerializerConfig.Builder().setLogMissingFields(false)
        .registerSerializer(new ClassSerializer<Base, String>() {
          @Override
          public Base deserialize(String from) {
            return new Base();
          }
        }).build();
    for (boolean json : new boolean[] {false, true}) {
      IllegalStateException error = Assertions.assertThrows(IllegalStateException.class,
          () -> TypeReauditRegressionTest.read(json ? "{\"value\": \"wrong\"}" : "value: wrong\n",
              new ConcreteSettings(), config, json));
      Assertions.assertTrue(error.getMessage().contains("Expected"));
      Assertions.assertTrue(error.getMessage().contains(Concrete.class.getName()));
    }
  }

  private static <T> ClassSerializer<T, String> converter(Class<T> type, Function<String, T> parser) {
    return new ClassSerializer<>(type, String.class) {
      @Override
      public String serialize(T from) {
        return "wire:" + from;
      }

      @Override
      public T deserialize(String from) {
        return parser.apply(from.substring(5));
      }
    };
  }

  private static void read(String input, Object holder, SerializerConfig config, boolean json) {
    BufferedReader buffer = new BufferedReader(new StringReader(input));
    AbstractReader reader = json ? new JsonReader(buffer, config) : new YamlReader(buffer, config);
    reader.readSerializableObject(holder, holder.getClass());
  }

  private static String write(Object holder, SerializerConfig config, boolean json) throws IOException {
    StringWriter output = new StringWriter();
    try (BufferedWriter buffer = new BufferedWriter(output)) {
      AbstractWriter writer = json ? new JsonWriter(buffer, config) : new YamlWriter(config, buffer);
      writer.writeNode(holder, null);
    }
    return output.toString();
  }

  private interface Domain {
  }

  private static class Base {
  }

  private static class Concrete extends Base implements Domain {

    private final String text;

    private Concrete(String text) {
      this.text = text;
    }
  }

  private static class ConcreteSerializer extends ClassSerializer<Concrete, String> {

    @Override
    public String serialize(Concrete from) {
      return from.text;
    }

    @Override
    public Concrete deserialize(String from) {
      return new Concrete(from);
    }
  }

  private static class InterfaceSettings {

    @Serializer(ConcreteSerializer.class)
    private Domain value;
  }

  private static class BaseSettings {

    @Serializer(ConcreteSerializer.class)
    private Base value;
  }

  private static class ConcreteSettings {

    private Concrete value;
  }

  private static class UnrelatedSettings {

    @Serializer(ConcreteSerializer.class)
    private Integer value;
  }

  private static class PrimitiveSettings {

    private int integer = 7;
    private long large = 9;
    private short small = 3;
    private byte tiny = 2;
    private float decimal = 1.5F;
    private double precise = 2.25D;
    private boolean enabled = true;
    private char marker = 'Q';
  }
}
