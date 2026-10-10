<img src="https://elytrium.net/src/img/elytrium.webp" alt="Elytrium" align="right">

# Elytrium Java Serializer

[![Join our Discord](https://img.shields.io/discord/775778822334709780.svg?logo=discord&label=Discord)](https://ely.su/discord)

Java Library to (de-)serialize data in various formats (e.g. JSON, YAML)

Requires Java 21 or newer. The Gradle build uses a Java 21 toolchain.

## Features of Elytrium Java Serializer

- Zero dependencies
- Serialize supported POJOs without annotations or a base class
- Auto replacer of placeholders
- Ability to add comments and new lines to the fields

## Basic usage

### Setup

Elytrium Java Serializer is uploaded to the Maven Central repository, so you can just add it as a dependency to your maven or gradle project.

- Maven (pom.xml):
   ```xml
   <dependencies>
     <dependency>
       <groupId>net.elytrium</groupId>
       <artifactId>serializer</artifactId>
       <version>1.2.0</version>
     </dependency>
   </dependencies>
   ```
- Gradle (build.gradle.kts):
   ```kotlin
   dependencies {
     implementation("net.elytrium:serializer:1.2.0")
   }
   ```

### Without any modifications

1) Create the class
    ```java
    public class Settings {

      public String regularField = "regular value";
    }
    ```
2) Create YamlWriter
    ```java
    Settings settings = new Settings();
    try (BufferedWriter output = Files.newBufferedWriter(Path.of("config.yml"))) {
      new YamlWriter(output).writeNode(settings, null);
    }
    ```
3) Create YamlReader
    ```java
    Settings settings = new Settings();
    try (BufferedReader input = Files.newBufferedReader(Path.of("config.yml"))) {
      new YamlReader(input).readSerializableObject(settings, Settings.class);
    }
   ```

### With some modifications

1) Create the class that extends YamlSerializable. You can optionally modify the config and call ``YamlSerializable#setConfig(SerializerConfig)`` method. You can safely remove ``super(Settings.CONFIG)``.
    ```java
    public class Settings extends YamlSerializable {
    
      private static final SerializerConfig CONFIG = new SerializerConfig.Builder().build();

      Settings() {
        super(Settings.CONFIG);
      }

      public String regularField = "regular value";
    }
    ```
2) Instantiate it.
    ```java
    Settings settings = new Settings();
    settings.reload(Path.of("config.yml"));
    ```
3) Done!

## Pro usage

### Comments and New lines

```java
  @NewLine(amount = 3)
  @Comment(
      value = {
          @CommentValue(" SAME_LINE comment Line 1")
      },
      at = Comment.At.SAME_LINE
  )
  @Comment(
      value = {
          @CommentValue(" SAME_LINE APPEND second comment Line 1"),
          @CommentValue(type = CommentValue.Type.NEW_LINE),
          @CommentValue(" SAME_LINE APPEND second comment Line 2")
      },
      at = Comment.At.APPEND
  )
  public String regularField = "regular value";
```

### Final and Transient fields

Final fields - unmodifiable fields that will be saved in the config. \
Transient fields - unmodifiable fields that won't be saved in the config.

```java
  public final String finalField = "final";
  @Final
  public String finalFieldToo = "final";

  public transient String transientField = "transient";
  @Transient
  public String transientFieldToo = "transient";
```

### Placeholders

```java
  @RegisterPlaceholders({"PLACEHOLDER", "another-placeholder"})
  public String anotherStringWithPlaceholders = "{PLACEHOLDER} {ANOTHER_PLACEHOLDER}";
```

```java
  Assertions.assertEquals("value 1 value 2", Placeholders.replace(settings.anotherStringWithPlaceholders, "value 1", "value 2"));
```

### Custom Placeholders

Custom placeholder will be instantiated once for one SerializableConfig. \
Placeholders.replace will work even with custom placeholder.

```java
  @RegisterPlaceholders(value = {"PLACEHOLDER", "another-placeholder"}, replacer = StringPlaceholderReplacer.class)
  public String anotherStringWithPlaceholders = "{PLACEHOLDER} {ANOTHER_PLACEHOLDER}";
```


```java
  public class StringPlaceholderReplacer implements PlaceholderReplacer<String, String> {

    @Override
    public String replace(String value, String[] placeholders, Object... values) {
      for (int i = Math.min(values.length, placeholders.length) - 1; i >= 0; --i) {
        value = value.replace(placeholders[i], String.valueOf(values[i]));
      }
   
      return value;
    }
  }
```

### Custom Serializers

Custom serializers will be instantiated once for one SerializableConfig.

```java
  @Serializer(ExternalClassSerializer.class)
  public ExternalDeserializedClass testClass = new ExternalDeserializedClass();
```

```java
  public static class ExternalDeserializedClass {

    public long x;
    public long y;
    public long z;
    public ExternalNestedClass nestedClass;

    // Public constructor with no args should be created for deserializer to work
    public ExternalDeserializedClass() {
      this.x = 0;
      this.y = 0;
      this.z = 0;
      this.nestedClass = new ExternalNestedClass("sample-name");
    }

    public ExternalDeserializedClass(long x, long y, long z, String name) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.nestedClass = new ExternalNestedClass(name);
    }

    public static class ExternalNestedClass {

      private String name;

      // Public constructor with no args should be created for deserializer to work
      public ExternalNestedClass() {

      }

      public ExternalNestedClass(String name) {
        this.name = name;
      }
    }
  }
```

```java
  public class ExternalClassSerializer extends ClassSerializer<ExternalDeserializedClass, Map<String, Object>> {

    @SuppressWarnings("unchecked")
    protected ExternalClassSerializer() {
      super(ExternalDeserializedClass.class, (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @Override
    public Map<String, Object> serialize(ExternalDeserializedClass from) {
      return SerializerTest.map("field-x", from.x, "field-y", from.y, "field-z", from.z, "nested-class-name", from.nestedClass.name);
    }

    @Override
    public ExternalDeserializedClass deserialize(Map<String, Object> from) {
      return new ExternalDeserializedClass(
          (long) from.get("field-x"),
          (long) from.get("field-y"),
          (long) from.get("field-z"),
          (String) from.get("nested-class-name")
      );
    }
  }
```

### Custom serializers without @Serializer annotation

In case if you can't add @Serializer annotation or if you have multiple entries with similar classes, you can register the serializer in the config.

```java
  private static final SerializerConfig CONFIG = new SerializerConfig.Builder().registerSerializer(new PathSerializer()).registerSerializer(new ClassSerializer<>(String.class, String.class) {

    @Override
    public String serialize(String from) {
      return from == null ? "" : from;
    }
 
    @Override
    public String deserialize(String from) {
      return from.trim().isEmpty() ? null : from;
    }
  }).build();
```

## Format and lifecycle contracts

- YAML supports mappings, sequences, quoted strings and literal/folded block strings. It is not a complete YAML 1.2 implementation: anchors, aliases, tags and multiple documents are not supported.
- JSON output uses JSON strings, keys and finite numbers. `NaN` and infinities are rejected. Comments remain an optional extension; output containing comments requires a reader that supports them.
- POJO support covers fields declared in the concrete class. Inherited fields, generic POJOs and arrays are not part of the supported model; use a custom serializer for these types. Recursive object graphs are not supported.
- Visible POJO field names must remain unique after name conversion; read aliases must not refer to different visible fields. Ambiguous names are rejected. Boolean values must be `true` or `false` (case-insensitive), and Character values must decode to exactly one Java character.
- Untyped `Object` values resolve plain `true`/`false` to Boolean, integers to Long and decimal numbers to Double; quoted values remain strings. Applications upgrading from older versions should check code that expected untyped booleans to be strings.
- Registered serializers prefer the exact requested class, then its primitive/wrapper counterpart, then the class hierarchy. Primitive fields use their declared primitive class for the first conversion, so separate `int.class` and `Integer.class` registrations remain distinct.
- `load`/`reload` roll back field assignments on parsing failure. Side effects inside user-provided converters are the caller's responsibility. Use one thread per mutable configuration object; thread-safe extension caching does not make arbitrary converters thread-safe.
- Reader and writer objects do not close caller-owned streams. Close streams with try-with-resources as shown above.
- Prefer the object overloads of `Placeholders`: registrations use weak object identity and are cleaned on later registry access after the holder is collected. Deprecated integer overloads use a separate, manually managed registry; they cannot distinguish identity-hash collisions. Register one placeholder specification per object, or use a distinct holder with `replaceFor` when the same value needs different specifications.

## Building 

Run `./gradlew build` with a Java 21 JDK installed.

The build uses Kotlin DSL (`build.gradle.kts` and `settings.gradle.kts`). External dependency coordinates and versions, including JUnit and Checkstyle, are maintained in `gradle/libs.versions.toml`.

## Support

If you want to get help or donate to us, you can join our Discord server and talk to us here. \
Invite link: https://elytrium.net/discord
