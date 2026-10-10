package net.elytrium.serializer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

class PostfixTypeRegressionTest {

  private static final SerializerConfig DEFAULT_CONFIG = new SerializerConfig.Builder().setLogMissingFields(false).build();

  @Test
  void annotatedBaseConverterRoundTripsSubtypeField() throws IOException {
    for (boolean json : new boolean[] {false, true}) {
      SubtypeSettings settings = new SubtypeSettings();
      String output = PostfixTypeRegressionTest.write(settings, DEFAULT_CONFIG, json);
      Assertions.assertEquals(Map.of("value", "kept"), new Yaml().load(output));
      settings.value = null;
      PostfixTypeRegressionTest.read(output, settings, DEFAULT_CONFIG, json);
      Assertions.assertEquals("kept", settings.value.text);
      Assertions.assertEquals(Sub.class, settings.value.getClass());
    }
  }

  @Test
  void annotatedCollectionConverterRoundTripsListField() throws IOException {
    for (boolean json : new boolean[] {false, true}) {
      CollectionSettings settings = new CollectionSettings();
      String output = PostfixTypeRegressionTest.write(settings, DEFAULT_CONFIG, json);
      Assertions.assertEquals(Map.of("values", List.of("wire:first", "wire:second")), new Yaml().load(output));
      settings.values = null;
      PostfixTypeRegressionTest.read(output, settings, DEFAULT_CONFIG, json);
      Assertions.assertEquals(List.of("first", "second"), settings.values);
    }
  }

  @Test
  void annotatedBaseConverterRejectsActualBaseResultForSubtypeField() {
    for (boolean json : new boolean[] {false, true}) {
      IllegalStateException error = Assertions.assertThrows(IllegalStateException.class,
          () -> PostfixTypeRegressionTest.read(json ? "{\"value\":\"wrong\"}" : "value: wrong\n",
              new WrongResultSettings(), DEFAULT_CONFIG, json));
      Assertions.assertTrue(error.getMessage().contains("Expected"));
      Assertions.assertTrue(error.getMessage().contains(Sub.class.getName()));
    }
  }

  @Test
  void annotatedConverterRejectsUnrelatedFieldType() {
    for (boolean json : new boolean[] {false, true}) {
      Assertions.assertThrows(IllegalStateException.class,
          () -> PostfixTypeRegressionTest.read(json ? "{\"value\":\"wrong\"}" : "value: wrong\n",
              new UnrelatedSettings(), DEFAULT_CONFIG, json));
    }
  }

  @Test
  void registryUsesPrimitiveCounterpartBeforeHierarchyForAllEightTypes() {
    Class<?>[] primitives = {int.class, long.class, short.class, byte.class, float.class, double.class, boolean.class, char.class};
    Class<?>[] wrappers = {Integer.class, Long.class, Short.class, Byte.class, Float.class, Double.class, Boolean.class, Character.class};
    for (int i = 0; i < primitives.length; ++i) {
      ClassSerializer<?, ?> primitive = PostfixTypeRegressionTest.converter(primitives[i], "primitive:", text -> null);
      ClassSerializer<?, ?> wrapper = PostfixTypeRegressionTest.converter(wrappers[i], "wrapper:", text -> null);
      ClassSerializer<?, ?> fallback = PostfixTypeRegressionTest.converter(Object.class, "object:", text -> text);
      SerializerConfig primitiveConfig = new SerializerConfig.Builder().registerSerializer(primitive).registerSerializer(fallback).build();
      SerializerConfig wrapperConfig = new SerializerConfig.Builder().registerSerializer(wrapper).registerSerializer(fallback).build();
      Assertions.assertSame(primitive, primitiveConfig.getRegisteredSerializer(wrappers[i]));
      Assertions.assertSame(wrapper, wrapperConfig.getRegisteredSerializer(primitives[i]));
      for (boolean primitiveFirst : new boolean[] {false, true}) {
        SerializerConfig both = new SerializerConfig.Builder()
            .registerSerializer(primitiveFirst ? primitive : wrapper)
            .registerSerializer(primitiveFirst ? wrapper : primitive).build();
        Assertions.assertSame(primitive, both.getRegisteredSerializer(primitives[i]));
        Assertions.assertSame(wrapper, both.getRegisteredSerializer(wrappers[i]));
      }
    }
  }

  @Test
  void primitiveLookupFallsBackToBoxedHierarchyAfterCounterpart() {
    ClassSerializer<Number, String> number = PostfixTypeRegressionTest.converter(Number.class, "number:", Integer::valueOf);
    ClassSerializer<Object, String> fallback = PostfixTypeRegressionTest.converter(Object.class, "object:", text -> text);
    SerializerConfig hierarchy = new SerializerConfig.Builder().registerSerializer(fallback).registerSerializer(number).build();
    Assertions.assertSame(number, hierarchy.getRegisteredSerializer(int.class));
    Assertions.assertSame(number, hierarchy.getRegisteredSerializer(Integer.class));
    ClassSerializer<Integer, String> integer = PostfixTypeRegressionTest.converter(Integer.class, "integer:", Integer::valueOf);
    SerializerConfig counterpart = new SerializerConfig.Builder().registerSerializer(number).registerSerializer(integer).build();
    Assertions.assertSame(integer, counterpart.getRegisteredSerializer(int.class));
  }

  @Test
  void primitiveRegisteredConvertersRoundTripAllEightFields() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().setLogMissingFields(false)
        .registerSerializer(PostfixTypeRegressionTest.converter(int.class, "wire:", Integer::valueOf))
        .registerSerializer(PostfixTypeRegressionTest.converter(long.class, "wire:", Long::valueOf))
        .registerSerializer(PostfixTypeRegressionTest.converter(short.class, "wire:", Short::valueOf))
        .registerSerializer(PostfixTypeRegressionTest.converter(byte.class, "wire:", Byte::valueOf))
        .registerSerializer(PostfixTypeRegressionTest.converter(float.class, "wire:", Float::valueOf))
        .registerSerializer(PostfixTypeRegressionTest.converter(double.class, "wire:", Double::valueOf))
        .registerSerializer(PostfixTypeRegressionTest.converter(boolean.class, "wire:", Boolean::valueOf))
        .registerSerializer(PostfixTypeRegressionTest.converter(char.class, "wire:", text -> text.charAt(0))).build();
    for (boolean json : new boolean[] {false, true}) {
      String output = PostfixTypeRegressionTest.write(new PrimitiveSettings(), config, json);
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
      PostfixTypeRegressionTest.read(output, result, config, json);
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
  void primitiveAndWrapperRegistrationsSelectExactDeclaredFieldType() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().setLogMissingFields(false)
        .registerSerializer(PostfixTypeRegressionTest.converter(int.class, "primitive:", Integer::valueOf))
        .registerSerializer(PostfixTypeRegressionTest.converter(Integer.class, "wrapper:", Integer::valueOf)).build();
    for (boolean json : new boolean[] {false, true}) {
      String output = PostfixTypeRegressionTest.write(new PairedSettings(), config, json);
      Assertions.assertEquals(Map.of("primitive", "primitive:7", "wrapper", "wrapper:9"), new Yaml().load(output));
      PairedSettings settings = new PairedSettings();
      PostfixTypeRegressionTest.read(json ? "{\"primitive\":\"primitive:11\",\"wrapper\":\"wrapper:13\"}"
          : "primitive: 'primitive:11'\nwrapper: 'wrapper:13'\n", settings, config, json);
      Assertions.assertEquals(11, settings.primitive);
      Assertions.assertEquals(13, settings.wrapper);
    }
  }

  @Test
  void explicitPrimitiveScalingConverterDecodesEightAsEightThousand() {
    SerializerConfig config = new SerializerConfig.Builder().setLogMissingFields(false)
        .registerSerializer(new ClassSerializer<Integer, String>(int.class, String.class) {
          @Override
          public Integer deserialize(String from) {
            return Integer.parseInt(from) * 1000;
          }
        }).build();
    for (boolean json : new boolean[] {false, true}) {
      ScalingSettings settings = new ScalingSettings();
      PostfixTypeRegressionTest.read(json ? "{\"value\":\"8\"}" : "value: '8'\n", settings, config, json);
      Assertions.assertEquals(8000, settings.value);
    }
  }

  @Test
  void annotationChainContinuesWhenWireInterfaceEqualsDeclaredFieldType() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().setLogMissingFields(false).registerSerializer(new DomainToString()).build();
    for (boolean json : new boolean[] {false, true}) {
      InterfaceChainSettings settings = new InterfaceChainSettings();
      String output = PostfixTypeRegressionTest.write(settings, config, json);
      Assertions.assertEquals(Map.of("value", "kept"), new Yaml().load(output));
      settings.value = null;
      PostfixTypeRegressionTest.read(output, settings, config, json);
      Assertions.assertEquals("kept", settings.value.text());
      Assertions.assertEquals(Concrete.class, settings.value.getClass());
    }
  }

  @Test
  void annotationChainRejectsRealCycleThroughModelType() {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new ClassSerializer<Domain, Concrete>(Domain.class, Concrete.class) {
      @Override
      public Domain deserialize(Concrete from) {
        return from;
      }
    }).build();
    for (boolean json : new boolean[] {false, true}) {
      IllegalStateException error = Assertions.assertThrows(IllegalStateException.class,
          () -> PostfixTypeRegressionTest.read(json ? "{\"value\":\"cycle\"}" : "value: cycle\n",
              new InterfaceChainSettings(), config, json));
      Assertions.assertTrue(error.getMessage().contains("cycle"));
    }
  }

  @Test
  void objectFallbackIsAppliedOnceToItsOwnWireType() {
    ClassSerializer<Object, String> fallback = PostfixTypeRegressionTest.converter(Object.class, "wire:", text -> text);
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(fallback).build();
    for (boolean json : new boolean[] {false, true}) {
      Object value = PostfixTypeRegressionTest.reader(json ? "\"wire:kept\"" : "'wire:kept'", config, json).readByType(Object.class);
      Assertions.assertEquals("kept", value);
    }
  }

  @Test
  void objectIdentityConverterKeepsQuotedAndPlainMapKeysDistinct() {
    SerializerConfig config = PostfixTypeRegressionTest.objectIdentityConfig();
    for (String source : List.of("{'12': quotedNumber, 12: number, 'true': quotedBoolean, true: boolean, 'null': quotedNull, null: absent}",
        "'12': quotedNumber\n12: number\n'true': quotedBoolean\ntrue: boolean\n'null': quotedNull\nnull: absent\n")) {
      Map<Object, Object> decoded = new YamlReader(new BufferedReader(new StringReader(source)), config).readMap(Object.class, Object.class);
      Assertions.assertEquals(6, decoded.size());
      Assertions.assertEquals("quotedNumber", decoded.get("12"));
      Assertions.assertEquals("number", decoded.get(12L));
      Assertions.assertEquals("quotedBoolean", decoded.get("true"));
      Assertions.assertEquals("boolean", decoded.get(true));
      Assertions.assertEquals("quotedNull", decoded.get("null"));
      Assertions.assertEquals("absent", decoded.get(null));
    }
  }

  @Test
  void objectIdentityConverterKeepsJsonMapKeysAsQuotedStrings() {
    Map<Object, Object> decoded = PostfixTypeRegressionTest.reader("{\"12\":12,\"true\":true,\"null\":null}",
        PostfixTypeRegressionTest.objectIdentityConfig(), true).readMap(Object.class, Object.class);
    Assertions.assertEquals(3, decoded.size());
    Assertions.assertEquals(12L, decoded.get("12"));
    Assertions.assertEquals(true, decoded.get("true"));
    Assertions.assertTrue(decoded.containsKey("null"));
    Assertions.assertNull(decoded.get("null"));
  }

  @Test
  void typedMapKeyConverterReceivesResolvedObjectWireScalar() {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new ClassSerializer<ScalarKey, Object>(ScalarKey.class, Object.class) {
      @Override
      public ScalarKey deserialize(Object from) {
        return new ScalarKey(from);
      }
    }).build();
    Map<Object, Object> decoded = new YamlReader(new BufferedReader(new StringReader("{'12': quoted, 12: number, true: boolean}")), config)
        .readMap(ScalarKey.class, Object.class);
    Assertions.assertEquals(3, decoded.size());
    Assertions.assertEquals("quoted", decoded.get(new ScalarKey("12")));
    Assertions.assertEquals("number", decoded.get(new ScalarKey(12L)));
    Assertions.assertEquals("boolean", decoded.get(new ScalarKey(true)));
  }

  @Test
  void primitiveToWrapperConverterContinuesThroughRegisteredStringStage() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().setLogMissingFields(false)
        .registerSerializer(PostfixTypeRegressionTest.primitiveToWrapper())
        .registerSerializer(PostfixTypeRegressionTest.converter(Integer.class, "wire:", Integer::valueOf)).build();
    for (boolean json : new boolean[] {false, true}) {
      String output = PostfixTypeRegressionTest.write(new PairedSettings(), config, json);
      Assertions.assertEquals(Map.of("primitive", "wire:17", "wrapper", "wire:9"), new Yaml().load(output));
      PairedSettings result = new PairedSettings();
      result.primitive = 0;
      result.wrapper = 0;
      PostfixTypeRegressionTest.read(output, result, config, json);
      Assertions.assertEquals(7, result.primitive);
      Assertions.assertEquals(9, result.wrapper);
    }
  }

  @Test
  void primitiveToWrapperCounterpartFallbackAppliesConverterOnce() throws IOException {
    SerializerConfig config = new SerializerConfig.Builder().setLogMissingFields(false)
        .registerSerializer(PostfixTypeRegressionTest.primitiveToWrapper()).build();
    for (boolean json : new boolean[] {false, true}) {
      PairedSettings settings = new PairedSettings();
      String output = PostfixTypeRegressionTest.write(settings, config, json);
      Assertions.assertEquals(Map.of("primitive", 17, "wrapper", 19), new Yaml().load(output));
      settings.primitive = 0;
      settings.wrapper = 0;
      PostfixTypeRegressionTest.read(output, settings, config, json);
      Assertions.assertEquals(7, settings.primitive);
      Assertions.assertEquals(9, settings.wrapper);
    }
  }

  @Test
  void primitiveToWrapperChainRejectsCycleReturningToPrimitive() {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(PostfixTypeRegressionTest.primitiveToWrapper())
        .registerSerializer(new ClassSerializer<Integer, Integer>(Integer.class, int.class) {
          @Override
          public Integer deserialize(Integer from) {
            return from;
          }
        }).build();
    for (boolean json : new boolean[] {false, true}) {
      IllegalStateException error = Assertions.assertThrows(IllegalStateException.class,
          () -> PostfixTypeRegressionTest.read(json ? "{\"value\":17}" : "value: 17\n", new ScalingSettings(), config, json));
      Assertions.assertTrue(error.getMessage().contains("cycle"));
    }
  }

  @Test
  void directReadNumberPreservesLegacyLongAndDoubleRuntimeTypes() {
    Class<?>[] integralTypes = {byte.class, Byte.class, short.class, Short.class, int.class, Integer.class, long.class, Long.class, Number.class};
    Class<?>[] decimalTypes = {float.class, Float.class, double.class, Double.class};
    for (boolean json : new boolean[] {false, true}) {
      for (Class<?> type : integralTypes) {
        Number value = PostfixTypeRegressionTest.reader("37", DEFAULT_CONFIG, json).readNumber(type);
        Assertions.assertEquals(37L, value);
        Assertions.assertEquals(Long.class, value.getClass());
      }
      for (Class<?> type : decimalTypes) {
        Number value = PostfixTypeRegressionTest.reader("37.5", DEFAULT_CONFIG, json).readNumber(type);
        Assertions.assertEquals(37.5D, value);
        Assertions.assertEquals(Double.class, value.getClass());
      }
    }
  }

  @Test
  void readByTypeStillNormalizesAllNumericPrimitiveAndWrapperTypes() {
    Class<?>[] types = {byte.class, Byte.class, short.class, Short.class, int.class, Integer.class, long.class, Long.class,
        float.class, Float.class, double.class, Double.class};
    Number[] expected = {(byte) 7, (byte) 7, (short) 7, (short) 7, 7, 7, 7L, 7L, 7.5F, 7.5F, 7.5D, 7.5D};
    for (boolean json : new boolean[] {false, true}) {
      for (int i = 0; i < types.length; ++i) {
        Object value = PostfixTypeRegressionTest.reader(i < 8 ? "7" : "7.5", DEFAULT_CONFIG, json).readByType(types[i]);
        Assertions.assertEquals(expected[i], value);
        Assertions.assertEquals(expected[i].getClass(), value.getClass());
      }
    }
  }

  @Test
  void numericOverflowKeepsValidationAndLegacySafeModeFallbackTypes() {
    SerializerConfig safe = new SerializerConfig.Builder().setSafeMode(true).build();
    Class<?>[] types = {byte.class, short.class, int.class, float.class};
    String[] source = {"128", "32768", "2147483648", "1e40"};
    Number[] expectedRaw = {0L, 0L, 0L, 0.0D};
    Number[] expectedTyped = {(byte) 0, (short) 0, 0, 0.0F};
    for (boolean json : new boolean[] {false, true}) {
      for (int i = 0; i < types.length; ++i) {
        Class<?> type = types[i];
        String input = source[i];
        RuntimeException error = Assertions.assertThrows(RuntimeException.class,
            () -> PostfixTypeRegressionTest.reader(input, DEFAULT_CONFIG, json).readNumber(type));
        Assertions.assertInstanceOf(NumberFormatException.class, error.getCause());
        Number raw = PostfixTypeRegressionTest.reader(input, safe, json).readNumber(type);
        Assertions.assertEquals(expectedRaw[i], raw);
        Assertions.assertEquals(expectedRaw[i].getClass(), raw.getClass());
        Object typed = PostfixTypeRegressionTest.reader(input, safe, json).readByType(type);
        Assertions.assertEquals(expectedTyped[i], typed);
        Assertions.assertEquals(expectedTyped[i].getClass(), typed.getClass());
      }
    }
  }

  @Test
  void numericWireConvertersReceiveNormalizedInput() {
    SerializerConfig integer = new SerializerConfig.Builder().registerSerializer(PostfixTypeRegressionTest.numericWire(Integer.class)).build();
    SerializerConfig decimal = new SerializerConfig.Builder().registerSerializer(PostfixTypeRegressionTest.numericWire(Float.class)).build();
    for (boolean json : new boolean[] {false, true}) {
      Assertions.assertEquals(new ScalarKey(7), PostfixTypeRegressionTest.reader("7", integer, json).readByType(ScalarKey.class));
      Assertions.assertEquals(new ScalarKey(7.5F), PostfixTypeRegressionTest.reader("7.5", decimal, json).readByType(ScalarKey.class));
    }
  }

  private static <T extends Number> ClassSerializer<ScalarKey, T> numericWire(Class<T> type) {
    return new ClassSerializer<>(ScalarKey.class, type) {
      @Override
      public ScalarKey deserialize(T from) {
        return new ScalarKey(from);
      }
    };
  }

  private static SerializerConfig objectIdentityConfig() {
    return new SerializerConfig.Builder().registerSerializer(new ClassSerializer<Object, Object>(Object.class, Object.class) {
      @Override
      public Object deserialize(Object from) {
        return from;
      }
    }).build();
  }

  private static ClassSerializer<Integer, Integer> primitiveToWrapper() {
    return new ClassSerializer<>(int.class, Integer.class) {
      @Override
      public Integer serialize(Integer from) {
        return from + 10;
      }

      @Override
      public Integer deserialize(Integer from) {
        return from - 10;
      }
    };
  }

  private static <T> ClassSerializer<T, String> converter(Class<T> type, String prefix, Function<String, T> parser) {
    return new ClassSerializer<>(type, String.class) {
      @Override
      public String serialize(T from) {
        return prefix + from;
      }

      @Override
      public T deserialize(String from) {
        Assertions.assertTrue(from.startsWith(prefix));
        return parser.apply(from.substring(prefix.length()));
      }
    };
  }

  private static AbstractReader reader(String input, SerializerConfig config, boolean json) {
    BufferedReader buffer = new BufferedReader(new StringReader(input));
    return json ? new JsonReader(buffer, config) : new YamlReader(buffer, config);
  }

  private static void read(String input, Object holder, SerializerConfig config, boolean json) {
    PostfixTypeRegressionTest.reader(input, config, json).readSerializableObject(holder, holder.getClass());
  }

  private static String write(Object holder, SerializerConfig config, boolean json) throws IOException {
    StringWriter output = new StringWriter();
    try (BufferedWriter buffer = new BufferedWriter(output)) {
      AbstractWriter writer = json ? new JsonWriter(buffer, config) : new YamlWriter(config, buffer);
      writer.writeNode(holder, null);
    }
    return output.toString();
  }

  private static class Base {

    protected final String text;

    private Base(String text) {
      this.text = text;
    }
  }

  private static class Sub extends Base {

    private Sub(String text) {
      super(text);
    }
  }

  private static class BroadSerializer extends ClassSerializer<Base, String> {

    @Override
    public String serialize(Base from) {
      return from.text;
    }

    @Override
    public Base deserialize(String from) {
      return new Sub(from);
    }
  }

  private static class WrongResultSerializer extends ClassSerializer<Base, String> {

    @Override
    public Base deserialize(String from) {
      return new Base(from);
    }
  }

  private static class CollectionSerializer extends ClassSerializer<Collection<String>, List<String>> {

    @Override
    public List<String> serialize(Collection<String> from) {
      return from.stream().map(text -> "wire:" + text).toList();
    }

    @Override
    public Collection<String> deserialize(List<String> from) {
      return new ArrayList<>(from.stream().map(text -> text.substring(5)).toList());
    }
  }

  private static class SubtypeSettings {

    @Serializer(BroadSerializer.class)
    private Sub value = new Sub("kept");
  }

  private static class WrongResultSettings {

    @Serializer(WrongResultSerializer.class)
    private Sub value;
  }

  private static class UnrelatedSettings {

    @Serializer(BroadSerializer.class)
    private Integer value;
  }

  private static class CollectionSettings {

    @Serializer(CollectionSerializer.class)
    private List<String> values = List.of("first", "second");
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

  private static class PairedSettings {

    private int primitive = 7;
    private Integer wrapper = 9;
  }

  private static class ScalingSettings {

    private int value;
  }

  private interface Domain {

    String text();
  }

  private record Concrete(String text) implements Domain {
  }

  private record WireDomain(String text) implements Domain {
  }

  private record ScalarKey(Object value) {
  }

  private static class ConcreteToDomain extends ClassSerializer<Concrete, Domain> {

    @Override
    public Domain serialize(Concrete from) {
      return new WireDomain(from.text());
    }

    @Override
    public Concrete deserialize(Domain from) {
      return new Concrete(from.text());
    }
  }

  private static class DomainToString extends ClassSerializer<Domain, String> {

    @Override
    public String serialize(Domain from) {
      return from.text();
    }

    @Override
    public Domain deserialize(String from) {
      return new WireDomain(from);
    }
  }

  private static class InterfaceChainSettings {

    @Serializer(ConcreteToDomain.class)
    private Domain value = new Concrete("kept");
  }
}
