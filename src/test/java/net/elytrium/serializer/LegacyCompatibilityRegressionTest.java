package net.elytrium.serializer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.elytrium.serializer.annotations.Comment;
import net.elytrium.serializer.annotations.CommentValue;
import net.elytrium.serializer.annotations.FallbackNodeNames;
import net.elytrium.serializer.annotations.Final;
import net.elytrium.serializer.annotations.NewLine;
import net.elytrium.serializer.annotations.OverrideNameStyle;
import net.elytrium.serializer.annotations.RegisterPlaceholders;
import net.elytrium.serializer.annotations.Serializer;
import net.elytrium.serializer.annotations.Transient;
import net.elytrium.serializer.annotations.YamlStringStyle;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.language.reader.AbstractReader;
import net.elytrium.serializer.language.reader.JsonReader;
import net.elytrium.serializer.language.reader.YamlReader;
import net.elytrium.serializer.language.writer.AbstractWriter;
import net.elytrium.serializer.language.writer.JsonWriter;
import net.elytrium.serializer.language.writer.YamlWriter;
import net.elytrium.serializer.placeholders.Placeholders;
import org.junit.jupiter.api.Test;

/**
 * Fixtures were produced by d0299c9df50a01ec8624e7fda6847b812ccf1944, not by the current writer.
 * The main method also lets the same client bytecode run against both library versions.
 */
public class LegacyCompatibilityRegressionTest {

  private static final String FIXTURES = "/compatibility/d0299c9d/";
  private static final List<String> CASES = List.of("scalars", "containers", "names", "comments", "serializers", "placeholders", "styles",
      "json-text", "json-number", "json-null");

  @Test
  void scalarTypesAndNullKeepTheirLegacyRepresentation() throws Exception {
    LegacyCompatibilityRegressionTest.verifyFixture("scalars");
  }

  @Test
  void nestedTypedContainersKeepTheirLegacyRepresentation() throws Exception {
    LegacyCompatibilityRegressionTest.verifyFixture("containers");
  }

  @Test
  void nameStylesAndIgnoredFieldsKeepTheirLegacyRepresentation() throws Exception {
    LegacyCompatibilityRegressionTest.verifyFixture("names");
  }

  @Test
  void commentsAndNewlinesKeepTheirLegacyRepresentation() throws Exception {
    LegacyCompatibilityRegressionTest.verifyFixture("comments");
  }

  @Test
  void registeredAndAnnotatedSerializersKeepTheirLegacyRepresentation() throws Exception {
    LegacyCompatibilityRegressionTest.verifyFixture("serializers");
  }

  @Test
  void legacySupportedJsonObjectsKeepTheirRepresentation() throws Exception {
    LegacyCompatibilityRegressionTest.verifyFixture("json-text");
    LegacyCompatibilityRegressionTest.verifyFixture("json-number");
    LegacyCompatibilityRegressionTest.verifyFixture("json-null");
  }

  @Test
  void placeholderRegistrationsKeepTheirLegacyRepresentation() throws Exception {
    LegacyCompatibilityRegressionTest.verifyFixture("placeholders");
  }

  @Test
  void yamlStringStylesKeepTheirLegacyRepresentation() throws Exception {
    LegacyCompatibilityRegressionTest.verifyFixture("styles");
  }

  private static void verifyFixture(String name) throws Exception {
    for (boolean json : LegacyCompatibilityRegressionTest.formats(name)) {
      String stem = name + (json ? ".json" : ".yml");
      String document = LegacyCompatibilityRegressionTest.resource(stem);
      Object original = LegacyCompatibilityRegressionTest.sample(name);
      LegacyCompatibilityRegressionTest.equal(LegacyCompatibilityRegressionTest.currentWire(name, document),
          LegacyCompatibilityRegressionTest.write(original, name, json), stem + " exact text, including documented format fixes");
      Object decoded = LegacyCompatibilityRegressionTest.read(document, name, json);
      LegacyCompatibilityRegressionTest.equal(LegacyCompatibilityRegressionTest.resource(stem + ".state"),
          LegacyCompatibilityRegressionTest.state(decoded), stem + " legacy decoded state");
      LegacyCompatibilityRegressionTest.equal(LegacyCompatibilityRegressionTest.state(original),
          LegacyCompatibilityRegressionTest.state(decoded), stem + " populated state");
      LegacyCompatibilityRegressionTest.verifyPlaceholders(decoded);
    }
  }

  /** Writes local artifacts and optionally reads artifacts produced by the other library version. */
  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    List<String> failures = new ArrayList<>();
    for (String name : CASES) {
      for (boolean json : LegacyCompatibilityRegressionTest.formats(name)) {
        String stem = name + (json ? ".json" : ".yml");
        try {
          Object original = LegacyCompatibilityRegressionTest.sample(name);
          String document = LegacyCompatibilityRegressionTest.write(original, name, json);
          Files.writeString(output.resolve(stem), document, StandardCharsets.UTF_8);
          Object decoded = LegacyCompatibilityRegressionTest.read(document, name, json);
          String state = LegacyCompatibilityRegressionTest.state(decoded);
          Files.writeString(output.resolve(stem + ".state"), state, StandardCharsets.UTF_8);
          LegacyCompatibilityRegressionTest.equal(LegacyCompatibilityRegressionTest.state(original), state, stem + " round trip");
          LegacyCompatibilityRegressionTest.verifyPlaceholders(decoded);
          if (args.length > 1) {
            String other = Files.readString(Path.of(args[1]).resolve(stem), StandardCharsets.UTF_8);
            LegacyCompatibilityRegressionTest.equal(LegacyCompatibilityRegressionTest.currentWire(name, other),
                LegacyCompatibilityRegressionTest.currentWire(name, document), stem + " exact text, including documented format fixes");
            if (!other.equals(document)) {
              System.out.println("DOCUMENTED FORMAT CHANGE " + stem);
            }
            Object crossDecoded = LegacyCompatibilityRegressionTest.read(other, name, json);
            LegacyCompatibilityRegressionTest.equal(state, LegacyCompatibilityRegressionTest.state(crossDecoded), stem + " cross read");
            LegacyCompatibilityRegressionTest.verifyPlaceholders(crossDecoded);
          }
          System.out.println("PASS " + stem);
        } catch (Throwable error) {
          failures.add(stem + ": " + error);
          error.printStackTrace(System.out);
        }
      }
    }
    if (!failures.isEmpty()) {
      throw new AssertionError(failures);
    }
  }

  private static boolean[] formats(String name) {
    return new boolean[] {name.startsWith("json-")};
  }

  private static String currentWire(String name, String legacy) {
    // Characters now use quoted YAML strings. Explicit block indentation protects leading spaces.
    if (name.equals("scalars")) {
      return legacy.replace("char-value: Z\n", "char-value: \"Z\"\n").replace("boxed-char: x\n", "boxed-char: \"x\"\n");
    }
    if (name.equals("styles")) {
      return legacy.replace("literal: |- \n", "literal: |-2\n").replace("folded: >-\n", "folded: >-2\n");
    }
    return legacy;
  }

  private static String resource(String name) throws IOException {
    try (InputStream input = Objects.requireNonNull(LegacyCompatibilityRegressionTest.class.getResourceAsStream(FIXTURES + name))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void equal(Object expected, Object actual, String context) {
    if (!Objects.equals(expected, actual)) {
      throw new AssertionError(context + "\nExpected: " + expected + "\nActual: " + actual);
    }
  }

  private static SerializerConfig config(String name) {
    SerializerConfig.Builder builder = new SerializerConfig.Builder().setLineSeparator("\n").setLogMissingFields(false);
    if (name.equals("names")) {
      builder.setNodeNameStyle(NameStyle.SNAKE_CASE);
    }
    if (name.equals("comments")) {
      builder.setCommentValueIndent(1);
    }
    if (name.equals("serializers")) {
      builder.registerSerializer(new TokenSerializer());
    }
    return builder.build();
  }

  private static Object fresh(String name) {
    switch (name) {
      case "scalars":
        return new Scalars();
      case "containers":
        return new Containers();
      case "names":
        return new Names();
      case "comments":
        return new Comments();
      case "serializers":
        return new Converters();
      case "placeholders":
        return new Messages();
      case "styles":
        return new Styles();
      case "json-text":
        return new JsonText();
      case "json-number":
        return new JsonNumber();
      case "json-null":
        return new JsonNull();
      default:
        throw new IllegalArgumentException(name);
    }
  }

  private static Object sample(String name) {
    Object result = LegacyCompatibilityRegressionTest.fresh(name);
    if (result instanceof Scalars) {
      Scalars value = (Scalars) result;
      value.byteValue = -12;
      value.shortValue = 32000;
      value.intValue = 123456;
      value.longValue = 9876543210L;
      value.floatValue = 1.25F;
      value.doubleValue = -2.5D;
      value.booleanValue = true;
      value.charValue = 'Z';
      value.boxedBytes = Arrays.asList((byte) 12, (byte) -12);
      value.boxedShorts = Arrays.asList((short) -32000, (short) 32000);
      value.boxedInts = Arrays.asList(-123456, 123456);
      value.boxedLong = -9876543210L;
      value.boxedFloats = Arrays.asList(-1.25F, 1.25F);
      value.boxedDouble = 2.5D;
      value.boxedBoolean = false;
      value.boxedChar = 'x';
      value.text = "hello world";
      value.nullValue = null;
      value.mode = Mode.SECOND;
    } else if (result instanceof Containers) {
      Containers value = (Containers) result;
      value.labels = Arrays.asList("one", "two", "three");
      value.numbers = Arrays.asList(10, -20, 30);
      value.rows = Arrays.asList(new Item("alpha", 11), new Item("beta", 22));
      value.grouped = new LinkedHashMap<>();
      value.grouped.put("first", Arrays.asList(1, 2));
      value.grouped.put("second", Arrays.asList(3, 4));
      value.byId = new LinkedHashMap<>();
      value.byId.put(7, new Item("seven", 77));
      value.byId.put(8, new Item("eight", 88));
      value.child = new Item("nested", 33);
    } else if (result instanceof Names) {
      Names value = (Names) result;
      value.firstValue = "renamed";
      value.overrideName = "override";
      value.fallbackValue = "canonical";
      value.version2Value = 42;
    } else if (result instanceof Comments) {
      Comments value = (Comments) result;
      value.firstValue = "commented";
      value.secondValue = 23;
    } else if (result instanceof Converters) {
      Converters value = (Converters) result;
      value.token = new Token("direct");
      value.tokens = Arrays.asList(new Token("first"), new Token("second"));
      value.subtype = new Sub("subtype");
    } else if (result instanceof Messages) {
      Messages value = (Messages) result;
      value.greeting = "Hello {NAME}";
      value.lines = Arrays.asList("Dear {NAME}", "Bye {NAME}");
    } else if (result instanceof Styles) {
      Styles value = (Styles) result;
      value.single = "it's quoted";
      value.doubleQuoted = "double \"quoted\"";
      value.literal = "first line\nsecond line";
      value.folded = "first line\nsecond line";
    } else if (result instanceof JsonText) {
      ((JsonText) result).value = "legacy text";
    } else if (result instanceof JsonNumber) {
      ((JsonNumber) result).value = 42;
    } else if (result instanceof JsonNull) {
      ((JsonNull) result).value = null;
    }
    return result;
  }

  private static String write(Object value, String name, boolean json) throws IOException {
    StringWriter result = new StringWriter();
    BufferedWriter output = new BufferedWriter(result);
    AbstractWriter writer = json ? new JsonWriter(output, LegacyCompatibilityRegressionTest.config(name))
        : new YamlWriter(LegacyCompatibilityRegressionTest.config(name), output);
    writer.writeNode(value, null);
    writer.flush();
    return result.toString();
  }

  private static Object read(String document, String name, boolean json) {
    Object result = LegacyCompatibilityRegressionTest.fresh(name);
    BufferedReader input = new BufferedReader(new StringReader(document));
    AbstractReader reader = json ? new BoundedJsonReader(input, LegacyCompatibilityRegressionTest.config(name))
        : new BoundedYamlReader(input, LegacyCompatibilityRegressionTest.config(name));
    reader.readSerializableObject(result, result.getClass());
    return result;
  }

  private static void verifyPlaceholders(Object decoded) {
    if (decoded instanceof Messages) {
      Messages value = (Messages) decoded;
      try {
        LegacyCompatibilityRegressionTest.equal("Hello Ada", Placeholders.replace(value.greeting, "Ada"), "string placeholder");
        LegacyCompatibilityRegressionTest.equal(Arrays.asList("Dear Ada", "Bye Ada"), Placeholders.replace(value.lines, "Ada"), "list placeholders");
      } finally {
        Placeholders.removePlaceholders(value.greeting);
        Placeholders.removePlaceholders(value.lines);
        for (String line : value.lines) {
          Placeholders.removePlaceholders(line);
        }
      }
    }
  }

  private static String state(Object value) throws IllegalAccessException {
    if (value == null) {
      return "null";
    }
    if (value instanceof CharSequence || value instanceof Character || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
      return value.getClass().getSimpleName() + "(" + value.toString().replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r") + ")";
    }
    StringBuilder result = new StringBuilder();
    if (value instanceof Collection<?>) {
      result.append("[");
      for (Object entry : (Collection<?>) value) {
        result.append(LegacyCompatibilityRegressionTest.state(entry)).append(';');
      }
      return result.append(']').toString();
    }
    if (value instanceof Map<?, ?>) {
      result.append("{");
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        result.append(LegacyCompatibilityRegressionTest.state(entry.getKey())).append('=')
            .append(LegacyCompatibilityRegressionTest.state(entry.getValue())).append(';');
      }
      return result.append('}').toString();
    }
    result.append(value.getClass().getSimpleName()).append('{');
    for (Field field : value.getClass().getFields()) {
      if (!Modifier.isStatic(field.getModifiers())) {
        result.append(field.getName()).append('=').append(LegacyCompatibilityRegressionTest.state(field.get(value))).append(';');
      }
    }
    return result.append('}').toString();
  }

  public enum Mode {
    FIRST, SECOND
  }

  public static class Scalars {
    public byte byteValue;
    public short shortValue;
    public int intValue;
    public long longValue;
    public float floatValue;
    public double doubleValue;
    public boolean booleanValue;
    public char charValue;
    public List<Byte> boxedBytes;
    public List<Short> boxedShorts;
    public List<Integer> boxedInts;
    public Long boxedLong;
    public List<Float> boxedFloats;
    public Double boxedDouble;
    public Boolean boxedBoolean;
    public Character boxedChar;
    public String text;
    public String nullValue = "must become null";
    public Mode mode;
  }

  public static class Containers {
    public List<String> labels;
    public List<Integer> numbers;
    public List<Item> rows;
    public Map<String, List<Integer>> grouped;
    public Map<Integer, Item> byId;
    public Item child;
  }

  public static class Item {
    public String name;
    public int count;

    public Item() {
    }

    public Item(String name, int count) {
      this.name = name;
      this.count = count;
    }
  }

  public static class Names {
    public String firstValue;
    @OverrideNameStyle(node = NameStyle.COBOL_CASE)
    public String overrideName;
    @FallbackNodeNames("old-value")
    public String fallbackValue;
    public int version2Value;
    public final String finalValue = "fixed";
    @Final
    public String annotatedFinal = "fixed too";
    public transient String ignoredValue = "ignored";
    @Transient
    public String annotatedIgnored = "ignored too";
  }

  @Comment(@CommentValue("Legacy header"))
  public static class Comments {
    @Comment(@CommentValue("Before first"))
    public String firstValue;
    @NewLine
    @Comment(value = @CommentValue("After second"), at = Comment.At.SAME_LINE)
    public int secondValue;
  }

  public static class Converters {
    public Token token;
    public List<Token> tokens;
    @Serializer(BaseSerializer.class)
    public Sub subtype;
  }

  public static class Token {
    public String text;

    public Token(String text) {
      this.text = text;
    }
  }

  public static class Base {
    public String text;

    public Base(String text) {
      this.text = text;
    }
  }

  public static class Sub extends Base {
    public Sub(String text) {
      super(text);
    }
  }

  public static class TokenSerializer extends ClassSerializer<Token, String> {
    public TokenSerializer() {
      super(Token.class, String.class);
    }

    @Override
    public String serialize(Token value) {
      return "wire:" + value.text;
    }

    @Override
    public Token deserialize(String value) {
      return new Token(value.substring(5));
    }
  }

  public static class BaseSerializer extends ClassSerializer<Sub, String> {
    public BaseSerializer() {
      super(Sub.class, String.class);
    }

    @Override
    public String serialize(Sub value) {
      return "base:" + value.text;
    }

    @Override
    public Sub deserialize(String value) {
      return new Sub(value.substring(5));
    }
  }

  public static class Messages {
    @RegisterPlaceholders("NAME")
    public String greeting;
    @RegisterPlaceholders("NAME")
    public List<String> lines;
  }

  public static class Styles {
    @YamlStringStyle(YamlWriter.StringStyle.SINGLE_QUOTED)
    public String single;
    @YamlStringStyle(YamlWriter.StringStyle.DOUBLE_QUOTED)
    public String doubleQuoted;
    @YamlStringStyle(YamlWriter.StringStyle.MULTILINE_LITERAL_AUTO_KEPT)
    public String literal;
    @YamlStringStyle(YamlWriter.StringStyle.MULTILINE_FOLDED_AUTO_KEPT)
    public String folded;
  }

  public static class JsonText {
    public String value;
  }

  public static class JsonNumber {
    public int value;
  }

  public static class JsonNull {
    public String value = "must become null";
  }

  private static class BoundedYamlReader extends YamlReader {
    private int remaining = 100000;

    BoundedYamlReader(BufferedReader input, SerializerConfig config) {
      super(input, config);
    }

    @Override
    public char readRaw() {
      if (--this.remaining < 0) {
        throw new IllegalStateException("Legacy reader exceeded 100000 operations");
      }
      return super.readRaw();
    }
  }

  private static class BoundedJsonReader extends JsonReader {
    private int remaining = 100000;

    BoundedJsonReader(BufferedReader input, SerializerConfig config) {
      super(input, config);
    }

    @Override
    public char readRaw() {
      if (--this.remaining < 0) {
        throw new IllegalStateException("Legacy reader exceeded 100000 operations");
      }
      return super.readRaw();
    }
  }
}
