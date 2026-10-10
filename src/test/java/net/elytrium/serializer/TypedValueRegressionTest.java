package net.elytrium.serializer;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import net.elytrium.serializer.annotations.CollectionType;
import net.elytrium.serializer.annotations.Serializer;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.language.reader.YamlReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TypedValueRegressionTest {

  @Test
  void readsGenericChangingSerializerOnce() {
    IntegerListSettings settings = new IntegerListSettings();
    GenericListSerializer serializer = new GenericListSerializer();
    TypedValueRegressionTest.read("values: ['12']\n", settings, serializer);
    Assertions.assertEquals(List.of(12), settings.values);
    Assertions.assertEquals(1, serializer.calls);
  }

  @Test
  void rejectsActualSerializerCycleBeforeReading() {
    ValueSettings settings = new ValueSettings();
    Assertions.assertThrows(IllegalStateException.class,
        () -> TypedValueRegressionTest.read("value: hello\n", settings, new ValueToMiddle(), new MiddleToValue()));
  }

  @Test
  void decodesSerializerChainInReverseOrder() {
    ValueSettings settings = new ValueSettings();
    TypedValueRegressionTest.read("value: hello\n", settings, new ValueToMiddle(), new MiddleToString());
    Assertions.assertEquals("hello", settings.value.text);
  }

  @Test
  void normalizesBoxedNumbersAndWireNumbers() {
    NumericSettings settings = new NumericSettings();
    TypedValueRegressionTest.read("integer: 12\nsmall: 13\ntiny: 14\ndecimal: 1.5\nvalue: 15\n", settings, new IntegerWireSerializer());
    Assertions.assertEquals(12, settings.integer);
    Assertions.assertEquals((short) 13, settings.small);
    Assertions.assertEquals((byte) 14, settings.tiny);
    Assertions.assertEquals(1.5F, settings.decimal);
    Assertions.assertEquals("15", settings.value.text);
  }

  @Test
  void rejectsNumericOverflowInsteadOfWrapping() {
    Assertions.assertThrows(RuntimeException.class, () -> TypedValueRegressionTest.read("integer: 2147483648\n", new PrimitiveNumericSettings()));
    Assertions.assertThrows(RuntimeException.class, () -> TypedValueRegressionTest.read("small: -32769\n", new PrimitiveNumericSettings()));
    Assertions.assertThrows(RuntimeException.class, () -> TypedValueRegressionTest.read("tiny: 128\n", new PrimitiveNumericSettings()));
    Assertions.assertThrows(RuntimeException.class, () -> TypedValueRegressionTest.read("decimal: 1e100\n", new PrimitiveNumericSettings()));
  }

  @Test
  void readsNumbersAtNarrowingBoundaries() {
    PrimitiveNumericSettings settings = new PrimitiveNumericSettings();
    TypedValueRegressionTest.read("integer: -2147483648\nsmall: 32767\ntiny: -128\ndecimal: 1.5\n", settings);
    Assertions.assertEquals(Integer.MIN_VALUE, settings.integer);
    Assertions.assertEquals(Short.MAX_VALUE, settings.small);
    Assertions.assertEquals(Byte.MIN_VALUE, settings.tiny);
    Assertions.assertEquals(1.5F, settings.decimal);
  }

  @Test
  void preservesNullReferenceFieldsAndEntries() {
    NullableSettings settings = new NullableSettings();
    TypedValueRegressionTest.read("integer: null\nflag: null\nvalue: null\nvalues: [null]\nafter: 8\n", settings);
    Assertions.assertNull(settings.integer);
    Assertions.assertNull(settings.flag);
    Assertions.assertNull(settings.value);
    Assertions.assertEquals(Arrays.asList((Integer) null), settings.values);
    Assertions.assertEquals(8, settings.after);
  }

  @Test
  void rejectsNullPrimitiveWithUsefulError() {
    IllegalStateException error = Assertions.assertThrows(IllegalStateException.class,
        () -> TypedValueRegressionTest.read("after: null\n", new NullableSettings()));
    Assertions.assertTrue(error.getMessage().contains("int"));
  }

  @Test
  void usesRegisteredSerializersInCollectionsAndMapKeysAndValues() {
    ContainerSettings settings = new ContainerSettings();
    TypedValueRegressionTest.read("values: [hello]\nmap: {hello: world}\n", settings, new ValueStringSerializer());
    Assertions.assertEquals("hello", settings.values.getFirst().text);
    Assertions.assertEquals("hello", settings.map.keySet().iterator().next().text);
    Assertions.assertEquals("world", settings.map.values().iterator().next().text);
  }

  @Test
  void usesSameTypeSerializerOnceInContainers() {
    StringContainerSettings settings = new StringContainerSettings();
    TypedValueRegressionTest.read("values: [hello]\nmap: {key: value}\n", settings, new SuffixSerializer());
    Assertions.assertEquals(List.of("hello!"), settings.values);
    Assertions.assertEquals(Map.of("key!", "value!"), settings.map);
  }

  @Test
  void preservesNestedConcreteCollectionClass() {
    NestedSettings settings = new NestedSettings();
    TypedValueRegressionTest.read("values:\n  - [hello]\n", settings);
    Assertions.assertInstanceOf(LinkedList.class, settings.values.getFirst());
    Assertions.assertEquals(List.of("hello"), settings.values.getFirst());
  }

  @Test
  void appliesCollectionAnnotationOnlyAtFieldLevel() {
    AnnotatedSettings settings = new AnnotatedSettings();
    TypedValueRegressionTest.read("values: [[hello]]\n", settings);
    Assertions.assertInstanceOf(HashSet.class, settings.values);
    Assertions.assertInstanceOf(List.class, settings.values.iterator().next());
  }

  @Test
  void resolvesNestedGenericInheritanceAndRawCollections() {
    GenericSettings settings = new GenericSettings();
    TypedValueRegressionTest.read("values: [[hello]]\nraw: [hello]\nmap: {key: value}\n", settings);
    Assertions.assertEquals(List.of(List.of("hello")), settings.values);
    Assertions.assertEquals(List.of("hello"), settings.raw);
    Assertions.assertEquals(Map.of("key", "value"), settings.map);
  }

  @Test
  void infersSerializerArgumentsThroughSubclass() {
    ValueSettings settings = new ValueSettings();
    TypedValueRegressionTest.read("value: hello\n", settings, new InheritedValueSerializer());
    Assertions.assertEquals("hello", settings.value.text);
  }

  @Test
  void usesAnnotatedSerializerAndThenRegisteredWireSerializer() {
    AnnotatedValueSettings settings = new AnnotatedValueSettings();
    TypedValueRegressionTest.read("value: hello\n", settings, new MiddleToString());
    Assertions.assertEquals("hello", settings.value.text);
  }

  @Test
  void stopsInheritedObjectSerializerAtItsWireType() {
    ObjectSettings settings = new ObjectSettings();
    TypedValueRegressionTest.read("value: hello\n", settings, new ObjectWireSerializer());
    Assertions.assertEquals("hello", Assertions.assertInstanceOf(Value.class, settings.value).text);
  }

  @Test
  void stopsInheritedCollectionSerializerAtItsWireType() {
    CollectionSettings settings = new CollectionSettings();
    TypedValueRegressionTest.read("values: [hello]\n", settings, new CollectionWireSerializer());
    Assertions.assertEquals(List.of("hello", "decoded"), settings.values);
  }

  @Test
  void stopsCollectionSerializerWhenDeclaredTypeAlreadyMatchesWireType() {
    StringContainerSettings settings = new StringContainerSettings();
    TypedValueRegressionTest.read("values: [hello]\n", settings, new CollectionWireSerializer());
    Assertions.assertEquals(List.of("hello", "decoded"), settings.values);
  }

  @Test
  void rejectsSerializerCycleWhenInitialTypeIsASubclass() {
    SubValueSettings settings = new SubValueSettings();
    Assertions.assertThrows(IllegalStateException.class,
        () -> TypedValueRegressionTest.read("value: hello\n", settings, new ValueToMiddle(), new MiddleToValue()));
  }

  @Test
  void acceptsLineFeedsFollowingCarriageReturnLineFeeds() {
    PrimitiveNumericSettings settings = new PrimitiveNumericSettings();
    TypedValueRegressionTest.read("integer: 1\r\nsmall: 2\ntiny: 3\r\n", settings);
    Assertions.assertEquals(1, settings.integer);
    Assertions.assertEquals(2, settings.small);
    Assertions.assertEquals(3, settings.tiny);
  }

  private static void read(String input, Object settings, ClassSerializer<?, ?>... serializers) {
    SerializerConfig.Builder builder = new SerializerConfig.Builder().setLogMissingFields(false);
    for (ClassSerializer<?, ?> serializer : serializers) {
      builder.registerSerializer(serializer);
    }
    new YamlReader(new BufferedReader(new StringReader(input)), builder.build()).readSerializableObject(settings, settings.getClass());
  }

  private static class IntegerListSettings {

    private List<Integer> values;
  }

  private static class GenericListSerializer extends ClassSerializer<List<Integer>, List<String>> {

    private int typeChecks;
    private int calls;

    @Override
    public Type getToType() {
      if (++this.typeChecks > 16) {
        throw new AssertionError("Unbounded generic serializer pipeline");
      }
      return super.getToType();
    }

    @Override
    public List<Integer> deserialize(List<String> from) {
      ++this.calls;
      return from.stream().map(Integer::valueOf).toList();
    }
  }

  private static class Value {

    private String text;

    private Value() {
    }

    private Value(String text) {
      this.text = text;
    }
  }

  private static class Middle {

    private final String text;

    private Middle(String text) {
      this.text = text;
    }
  }

  private static class ValueSettings {

    private Value value = new Value("default");
  }

  private static class SubValue extends Value {
  }

  private static class SubValueSettings {

    private SubValue value;
  }

  private static class ObjectSettings {

    private Object value;
  }

  private static class CollectionSettings {

    private Collection<String> values;
  }

  private static class AnnotatedValueSettings {

    @Serializer(ValueToMiddle.class)
    private Value value = new Value("default");
  }

  private static class ValueToMiddle extends ClassSerializer<Value, Middle> {

    private int typeChecks;

    @Override
    public Type getToType() {
      if (++this.typeChecks > 16) {
        throw new AssertionError("Unbounded serializer cycle");
      }
      return super.getToType();
    }

    @Override
    public Value deserialize(Middle from) {
      return new Value(from.text);
    }
  }

  private static class MiddleToValue extends ClassSerializer<Middle, Value> {

    @Override
    public Middle deserialize(Value from) {
      return new Middle(from.text);
    }
  }

  private static class MiddleToString extends ClassSerializer<Middle, String> {

    @Override
    public Middle deserialize(String from) {
      return new Middle(from);
    }
  }

  private static class IntegerWireSerializer extends ClassSerializer<Value, Integer> {

    @Override
    public Value deserialize(Integer from) {
      return new Value(from.toString());
    }
  }

  private static class ValueStringSerializer extends ClassSerializer<Value, String> {

    @Override
    public Value deserialize(String from) {
      return new Value(from);
    }
  }

  private static class InheritedValueSerializer extends ValueStringSerializer {
  }

  private static class SuffixSerializer extends ClassSerializer<String, String> {

    @Override
    public String deserialize(String from) {
      return from + "!";
    }
  }

  private static class ObjectWireSerializer extends ClassSerializer<Object, String> {

    @Override
    public Object deserialize(String from) {
      return new Value(from);
    }
  }

  private static class CollectionWireSerializer extends ClassSerializer<Collection<String>, List<String>> {

    @Override
    public Collection<String> deserialize(List<String> from) {
      List<String> result = new ArrayList<>(from);
      result.add("decoded");
      return result;
    }
  }

  private static class NumericSettings {

    private Integer integer;
    private Short small;
    private Byte tiny;
    private Float decimal;
    private Value value;
  }

  private static class NullableSettings {

    private Integer integer = 3;
    private Boolean flag = true;
    private Value value = new Value("default");
    private List<Integer> values;
    private int after;
  }

  private static class PrimitiveNumericSettings {

    private int integer;
    private short small;
    private byte tiny;
    private float decimal;
  }

  private static class ContainerSettings {

    private List<Value> values;
    private Map<Value, Value> map;
  }

  private static class StringContainerSettings {

    private List<String> values;
    private Map<String, String> map;
  }

  private static class NestedSettings {

    private List<LinkedList<String>> values;
  }

  private static class AnnotatedSettings {

    @CollectionType(HashSet.class)
    private Collection<List<String>> values;
  }

  private static class NestedGeneric<T> extends ArrayList<List<T>> {
  }

  @SuppressWarnings("rawtypes")
  private static class GenericSettings {

    private NestedGeneric<String> values;
    private List raw;
    private Map map;
  }
}
