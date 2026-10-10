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

package net.elytrium.serializer.language.reader;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.elytrium.serializer.SerializerConfig;
import net.elytrium.serializer.annotations.CollectionType;
import net.elytrium.serializer.annotations.MapType;
import net.elytrium.serializer.annotations.Serializer;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.exceptions.ReflectionException;
import net.elytrium.serializer.exceptions.SerializableReadException;
import net.elytrium.serializer.utils.GenericUtils;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractReader {

  private static final Logger LOGGER = Logger.getLogger(AbstractReader.class.getName());
  protected static final char NEW_LINE = '\n';

  private final char[] singleCharBuffer = new char[1];
  private final Deque<Character> replayBuffer = new ArrayDeque<>();
  private final Deque<Character> seekBuffer = new ArrayDeque<>();
  protected final SerializerConfig config;
  protected final BufferedReader reader;

  private boolean reuseBuffer;
  private boolean seekEnabled;
  private CarriageType carriageType = CarriageType.UNKNOWN;
  private boolean backupPreferred;
  private int sourcePushback = -1;
  private List<Runnable> rollbackActions;
  private List<Runnable> commitActions;

  public AbstractReader(SerializerConfig config, BufferedReader reader) {
    this.config = config;
    this.reader = reader;
  }

  public AbstractReader(BufferedReader reader) {
    this.config = SerializerConfig.DEFAULT;
    this.reader = reader;
  }

  public void readSerializableObject(Object holder, Class<?> clazz) {
    this.readSerializableObject(null, holder, clazz);
  }

  public abstract void readSerializableObject(@Nullable Field owner, Object holder, Class<?> clazz);

  public String readNodeName() {
    return this.readNodeName(null);
  }

  public abstract String readNodeName(@Nullable Field owner);

  public void readBeginSerializableObject() {
    this.readBeginSerializableObject(null);
  }

  public abstract void readBeginSerializableObject(@Nullable Field owner);

  public void readSerializableObjectEntryJoin() {
    this.readSerializableObjectEntryJoin(null);
  }

  public abstract void readSerializableObjectEntryJoin(@Nullable Field owner);

  public boolean readEndSerializableObject() {
    return this.readEndSerializableObject(null);
  }

  public abstract boolean readEndSerializableObject(@Nullable Field owner);

  public Object readNode(Object holder, Field node) {
    synchronized (this) {
      try {
        Object value = this.readByType(node, node.get(holder), node.getGenericType());
        this.assignField(holder, node, value);
        return value;
      } catch (IllegalAccessException e) {
        throw new ReflectionException(e);
      }
    }
  }

  protected void assignField(Object holder, Field node, Object value) throws IllegalAccessException {
    Object previous = node.get(holder);
    if (this.rollbackActions != null) {
      this.rollbackActions.add(() -> {
        try {
          node.set(holder, previous);
        } catch (IllegalAccessException e) {
          throw new ReflectionException(e);
        }
      });
    }
    node.set(holder, value);
  }

  public void beginTransaction() {
    if (this.rollbackActions != null) {
      throw new IllegalStateException("A read transaction is already active");
    }
    this.rollbackActions = new ArrayList<>();
    this.commitActions = new ArrayList<>();
  }

  public void commitTransaction() {
    if (this.commitActions != null) {
      for (Runnable action : this.commitActions) {
        action.run();
      }
    }
    this.rollbackActions = null;
    this.commitActions = null;
  }

  public void rollbackTransaction() {
    if (this.rollbackActions != null) {
      for (int i = this.rollbackActions.size() - 1; i >= 0; --i) {
        this.rollbackActions.get(i).run();
      }
    }
    this.rollbackActions = null;
    this.commitActions = null;
  }

  protected void afterCommit(Runnable action) {
    if (this.commitActions == null) {
      action.run();
    } else {
      this.commitActions.add(action);
    }
  }

  protected Type fillSerializerStack(Deque<ClassSerializer<?, Object>> serializerStack, Class<?> clazz) {
    return this.fillSerializerStack(serializerStack, null, clazz);
  }

  private Type fillSerializerStack(Deque<ClassSerializer<?, Object>> serializerStack, @Nullable Field owner, Type type) {
    Set<ClassSerializer<?, Object>> applied = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<Type> visited = new HashSet<>();
    Class<?> clazz = GenericUtils.unwrapClassParameterizedType(type);
    Serializer annotation = owner == null ? null : owner.getAnnotation(Serializer.class);
    if (annotation == null) {
      annotation = clazz.getAnnotation(Serializer.class);
    }
    while (true) {
      ClassSerializer<?, Object> classSerializer;
      boolean annotated = annotation != null;
      if (annotated) {
        try {
          classSerializer = this.config.getAndCacheSerializer(annotation);
        } catch (ReflectiveOperationException e) {
          throw new ReflectionException(e);
        }
        annotation = null;
      } else {
        classSerializer = this.config.getRegisteredSerializer(clazz);
      }
      if (classSerializer == null) {
        break;
      }
      Class<?> outputClass = AbstractReader.boxedClass(classSerializer.getToClass());
      Class<?> declaredClass = AbstractReader.boxedClass(clazz);
      // Related annotation types are allowed; the decoded result is checked against the actual field type.
      boolean compatible = outputClass.isAssignableFrom(declaredClass) || annotated && declaredClass.isAssignableFrom(outputClass);
      if (!compatible) {
        throw new IllegalStateException("Serializer " + classSerializer.getClass().getName() + " cannot decode " + type.getTypeName());
      }
      if (!applied.add(classSerializer)) {
        // A base-type registration may also match its own, more specific wire type.
        break;
      }
      visited.add(classSerializer.getToType());
      Type nextType = classSerializer.getFromType();
      Class<?> nextClass = classSerializer.getFromClass();
      serializerStack.addLast(classSerializer);
      // Compare the converter's model and wire classes, which may differ from the declared field class.
      if (classSerializer.getToClass() == nextClass) {
        type = nextType;
        break;
      }
      if (!visited.add(nextType)) {
        throw new IllegalStateException("Serializer cycle while decoding " + nextType.getTypeName());
      }
      type = nextType;
      clazz = nextClass;
    }
    return type;
  }

  private static Class<?> boxedClass(Class<?> clazz) {
    if (clazz == int.class) {
      return Integer.class;
    } else if (clazz == long.class) {
      return Long.class;
    } else if (clazz == short.class) {
      return Short.class;
    } else if (clazz == byte.class) {
      return Byte.class;
    } else if (clazz == float.class) {
      return Float.class;
    } else if (clazz == double.class) {
      return Double.class;
    } else if (clazz == boolean.class) {
      return Boolean.class;
    } else if (clazz == char.class) {
      return Character.class;
    }
    return clazz;
  }

  protected Object readAndDeserializeByType(@Nullable Field owner, Object holder, Type type, Deque<ClassSerializer<?, Object>> serializerStack) {
    return this.deserialize(this.readRawByType(owner, holder, type), serializerStack);
  }

  private Object deserialize(Object value, Deque<ClassSerializer<?, Object>> serializerStack) {
    while (!serializerStack.isEmpty()) {
      ClassSerializer<?, Object> classSerializer = serializerStack.removeLast();
      if (value != null) {
        value = this.normalizeValue(value, classSerializer.getFromClass());
        value = classSerializer.deserialize(value);
      }
    }
    return value;
  }

  public Object readByField(Field field) {
    return this.readByType(field, field.getGenericType());
  }

  public Object readByType(Type type) {
    return this.readByType(null, type);
  }

  public Object readByType(@Nullable Field owner, Type type) {
    // TODO Use old map value when possible while reading.
    return this.readByType(owner, null, type);
  }

  public Object readByType(@Nullable Object holder, Type type) {
    return this.readByType(null, holder, type);
  }

  public Object readByType(@Nullable Field owner, @Nullable Object holder, Type type) {
    synchronized (this) {
      Deque<ClassSerializer<?, Object>> serializers = new ArrayDeque<>();
      Type wireType = this.fillSerializerStack(serializers, owner, type);
      Object value = this.readAndDeserializeByType(owner, holder, wireType, serializers);
      return this.normalizeValue(value, GenericUtils.unwrapClassParameterizedType(type));
    }
  }

  protected boolean tryReadNull(@Nullable Field owner) {
    return false;
  }

  private Object readRawByType(@Nullable Field owner, @Nullable Object holder, Type type) {
    synchronized (this) {
      if (type == Object.class) {
        return this.readGuessingType(owner);
      } else if (type instanceof ParameterizedType parameterizedType) {
        Class<?> clazz = (Class<?>) parameterizedType.getRawType();
        return Map.class.isAssignableFrom(clazz) ? this.readMapByType(owner, parameterizedType)
            : Collection.class.isAssignableFrom(clazz) ? this.readCollectionByType(owner, parameterizedType, clazz)
            : this.readUnsupportedType(type);
      } else if (type instanceof Class<?> clazz) {
        if (Map.class.isAssignableFrom(clazz)) {
          return this.readMapByType(owner, type);
        } else if (Collection.class.isAssignableFrom(clazz)) {
          return this.readCollectionByType(owner, type, clazz);
        } else if (String.class.isAssignableFrom(clazz)) {
          return this.readString(owner);
        } else if (Character.class.isAssignableFrom(clazz) || char.class.isAssignableFrom(clazz)) {
          return this.readCharacter(owner);
        } else if (clazz.isEnum()) {
          return this.readEnum(owner, clazz);
        } else if (Boolean.class.isAssignableFrom(clazz) || boolean.class.isAssignableFrom(clazz)) {
          return this.readBoolean(owner);
        } else if (Number.class.isAssignableFrom(clazz) || clazz.isPrimitive()) {
          return this.readNumber(owner, clazz);
        } else {
          if (clazz.isArray()) {
            return this.readUnsupportedType(type);
          }
          if (this.tryReadNull(owner)) {
            return null;
          }
          try {
            Object result;
            if (clazz.isInstance(holder)) {
              result = holder;
            } else {
              Constructor<?> constructor = clazz.getDeclaredConstructor();
              constructor.setAccessible(true);
              result = constructor.newInstance();
            }

            this.readSerializableObject(owner, result, clazz);
            return result;
          } catch (ReflectiveOperationException e) {
            throw new SerializableReadException("Cannot construct " + clazz.getName() + "; register a serializer or provide a no-argument constructor", e);
          }
        }
      } else {
        throw new IllegalArgumentException("Invalid type was provided: " + type);
      }
    }
  }

  private Object readUnsupportedType(Type type) {
    throw new IllegalStateException("Unsupported type " + type.getTypeName() + "; register a serializer for this type");
  }

  @SuppressWarnings("unchecked")
  private Map<Object, Object> readMapByType(Field owner, Type type) {
    Type mapKeyType = GenericUtils.getParameterType(Map.class, type, 0);
    Type mapValueType = GenericUtils.getParameterType(Map.class, type, 1);
    MapType mapType = owner == null ? null : owner.getAnnotation(MapType.class);
    Class<?> clazz = mapType == null ? GenericUtils.unwrapClassParameterizedType(type) : mapType.value();
    if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())) {
      return this.readMap(owner, (Map<Object, Object>) this.newContainer(clazz), mapKeyType, mapValueType);
    }

    return this.readMap(owner, mapKeyType, mapValueType);
  }

  @SuppressWarnings("unchecked")
  private Collection<Object> readCollectionByType(Field owner, Type type, Class<?> clazz) {
    Type collectionEntryType = GenericUtils.getParameterType(Collection.class, type, 0);
    CollectionType collectionType = owner == null ? null : owner.getAnnotation(CollectionType.class);
    Class<?> implementation = collectionType == null ? clazz : collectionType.value();
    if (!implementation.isInterface() && !Modifier.isAbstract(implementation.getModifiers())) {
      return this.readCollection(owner, (Collection<Object>) this.newContainer(implementation), collectionEntryType);
    }

    return Set.class.isAssignableFrom(clazz) ? this.readSet(owner, collectionEntryType)
        : Queue.class.isAssignableFrom(clazz) ? this.readDeque(owner, collectionEntryType)
        : this.readList(owner, collectionEntryType);
  }

  private Object newContainer(Class<?> clazz) {
    try {
      Constructor<?> constructor = clazz.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new SerializableReadException("Cannot construct collection or map " + clazz.getName(), e);
    }
  }

  protected Object deserializeMapKey(String text, Type type) {
    Object objectWireValue;
    try {
      objectWireValue = Long.valueOf(text);
    } catch (NumberFormatException e) {
      try {
        objectWireValue = Double.valueOf(text);
      } catch (NumberFormatException ignored) {
        objectWireValue = text;
      }
    }
    return this.deserializeMapKey(text, type, objectWireValue);
  }

  protected Object deserializeMapKey(String text, Type type, @Nullable Object objectWireValue) {
    Deque<ClassSerializer<?, Object>> serializers = new ArrayDeque<>();
    Type wireType = this.fillSerializerStack(serializers, null, type);
    Class<?> clazz = GenericUtils.unwrapClassParameterizedType(wireType);
    Object value;
    if (clazz == String.class) {
      value = text;
    } else if (clazz == Object.class) {
      value = objectWireValue;
    } else if (clazz == Character.class || clazz == char.class) {
      if (text.length() != 1) {
        throw new IllegalStateException("Character map key must contain exactly one character");
      }
      value = text.charAt(0);
    } else if (clazz == Boolean.class || clazz == boolean.class) {
      if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
        throw new IllegalStateException("Invalid boolean map key: " + text);
      }
      value = Boolean.valueOf(text);
    } else if (clazz.isEnum()) {
      value = this.enumMapKey(clazz, text);
    } else if (Number.class.isAssignableFrom(clazz) || clazz.isPrimitive()) {
      value = clazz == Float.class || clazz == float.class || clazz == Double.class || clazz == double.class
          ? (Number) Double.valueOf(text) : (Number) Long.valueOf(text);
      value = this.normalizeValue(value, clazz);
    } else {
      throw new IllegalStateException("Unsupported map key type " + wireType.getTypeName() + "; register a scalar serializer");
    }
    return this.normalizeValue(this.deserialize(value, serializers), GenericUtils.unwrapClassParameterizedType(type));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Object enumMapKey(Class<?> clazz, String text) {
    return Enum.valueOf((Class<? extends Enum>) clazz, text);
  }

  private Object normalizeValue(Object value, Class<?> clazz) {
    if (value == null) {
      if (clazz.isPrimitive()) {
        throw new IllegalStateException("Cannot assign null to primitive " + clazz.getTypeName());
      }
      return null;
    }
    if (value instanceof Number number) {
      if (clazz == Integer.class || clazz == int.class) {
        long integral = number.longValue();
        if (integral < Integer.MIN_VALUE || integral > Integer.MAX_VALUE) {
          throw new NumberFormatException("Number out of range for int: " + number);
        }
        return (int) integral;
      } else if (clazz == Short.class || clazz == short.class) {
        long integral = number.longValue();
        if (integral < Short.MIN_VALUE || integral > Short.MAX_VALUE) {
          throw new NumberFormatException("Number out of range for short: " + number);
        }
        return (short) integral;
      } else if (clazz == Byte.class || clazz == byte.class) {
        long integral = number.longValue();
        if (integral < Byte.MIN_VALUE || integral > Byte.MAX_VALUE) {
          throw new NumberFormatException("Number out of range for byte: " + number);
        }
        return (byte) integral;
      } else if (clazz == Long.class || clazz == long.class) {
        return number.longValue();
      } else if (clazz == Float.class || clazz == float.class) {
        float result = number.floatValue();
        if (Float.isInfinite(result) && Double.isFinite(number.doubleValue())) {
          throw new NumberFormatException("Number out of range for float: " + number);
        }
        return result;
      } else if (clazz == Double.class || clazz == double.class) {
        return number.doubleValue();
      }
    }
    if (clazz.isInstance(value) || clazz == boolean.class && value instanceof Boolean || clazz == char.class && value instanceof Character) {
      return value;
    }
    throw new IllegalStateException("Expected " + clazz.getTypeName() + " but decoded " + value.getClass().getTypeName());
  }

  public Object readGuessingType() {
    return this.readGuessingType(null);
  }

  public abstract Object readGuessingType(@Nullable Field owner);

  public Map<Object, Object> readMap(@Nullable Field owner) {
    return this.readMap(owner, Object.class, Object.class);
  }

  public Map<Object, Object> readMap(Type keyType, Type valueType) {
    return this.readMap((Field) null, keyType, valueType);
  }

  public Map<Object, Object> readMap(@Nullable Field owner, Type keyType, Type valueType) {
    return this.readMap(owner, new LinkedHashMap<>(), keyType, valueType);
  }

  public <C extends Map<Object, Object>> C readMap(@Nullable Field owner, C result) {
    return this.readMap(owner, result, Object.class, Object.class);
  }

  public <C extends Map<Object, Object>> C readMap(C result, Type keyType, Type valueType) {
    return this.readMap(null, result, keyType, valueType);
  }

  public abstract <C extends Map<Object, Object>> C readMap(@Nullable Field owner, C result, Type keyType, Type valueType);

  public Set<Object> readSet(@Nullable Field owner) {
    return this.readSet(owner, Object.class);
  }

  public Set<Object> readSet(Type type) {
    return this.readSet(null, type);
  }

  public Set<Object> readSet(@Nullable Field owner, Type type) {
    return this.readCollection(owner, new HashSet<>(), type);
  }

  public Deque<Object> readDeque(@Nullable Field owner) {
    return this.readDeque(owner, Object.class);
  }

  public Deque<Object> readDeque(Type type) {
    return this.readDeque(null, type);
  }

  public Deque<Object> readDeque(@Nullable Field owner, Type type) {
    return this.readCollection(owner, new ArrayDeque<>(), type);
  }

  public List<Object> readList(@Nullable Field owner) {
    return this.readList(owner, Object.class);
  }

  public List<Object> readList(Type type) {
    return this.readList(null, type);
  }

  public List<Object> readList(@Nullable Field owner, Type type) {
    return this.readCollection(owner, new ArrayList<>(), type);
  }

  public <C extends Collection<Object>> C readCollection(@Nullable Field owner, C result) {
    return this.readCollection(owner, result, Object.class);
  }

  public <C extends Collection<Object>> C readCollection(C result, Type type) {
    return this.readCollection(null, result, type);
  }

  public abstract <C extends Collection<Object>> C readCollection(@Nullable Field owner, C result, Type type);

  public String readString() {
    return this.readString(null);
  }

  public abstract String readString(@Nullable Field owner);

  public Character readCharacter() {
    return this.readCharacter(null);
  }

  public abstract Character readCharacter(@Nullable Field owner);

  @SuppressWarnings("unchecked")
  public <T extends Enum<T>> T readEnum(@Nullable Field owner, Class<?> enumClass) {
    String enumValue = this.readString(owner);
    return enumValue == null ? null : Enum.valueOf((Class<T>) enumClass, enumValue);
  }

  public Boolean readBoolean() {
    return this.readBoolean(null);
  }

  public abstract Boolean readBoolean(@Nullable Field owner);

  public Number readNumber(Class<?> clazz) {
    return this.readNumber(null, clazz);
  }

  public Number readNumber(@Nullable Field owner, Class<?> clazz) {
    synchronized (this) {
      boolean decimal = Float.class.isAssignableFrom(clazz) || float.class.isAssignableFrom(clazz)
          || Double.class.isAssignableFrom(clazz) || double.class.isAssignableFrom(clazz);
      try {
        Number value = decimal ? (Number) this.readDouble(owner) : (Number) this.readLong(owner);
        // Validate the requested range while retaining the public API's Long/Double return types.
        this.normalizeValue(value, clazz);
        return value;
      } catch (NumberFormatException e) {
        if (this.config.isSafeMode()) {
          AbstractReader.LOGGER.log(Level.WARNING, "Can't read number due to exception caught, overwriting the value by 0", e);
          return decimal ? (Number) 0.0 : (Number) 0L;
        } else {
          throw new RuntimeException(e);
        }
      }
    }
  }

  public Double readDouble() {
    return this.readDouble(null);
  }

  public abstract Double readDouble(@Nullable Field owner);

  public Long readLong() {
    return this.readLong(null);
  }

  public abstract Long readLong(@Nullable Field owner);

  public void skipNode(Field node) {
    this.skipNode(node, node.getType());
  }

  public void skipNode(Class<?> clazz) {
    this.skipNode(null, clazz);
  }

  public void skipNode(@Nullable Field owner, Class<?> clazz) {
    if (Map.class.isAssignableFrom(clazz)) {
      this.skipMap(owner);
    } else if (Collection.class.isAssignableFrom(clazz)) {
      this.skipCollection(owner);
    } else if (clazz.isEnum()
        || String.class.isAssignableFrom(clazz)
        || Boolean.class.isAssignableFrom(clazz) || boolean.class.isAssignableFrom(clazz)
        || Character.class.isAssignableFrom(clazz) || char.class.isAssignableFrom(clazz)
        || Number.class.isAssignableFrom(clazz) || clazz.isPrimitive()) {
      this.skipString(owner);
    } else {
      this.skipGuessingType(owner);
    }
  }

  public void skipMap() {
    this.skipMap(null);
  }

  public abstract void skipMap(@Nullable Field owner);

  public void skipCollection() {
    this.skipCollection(null);
  }

  public abstract void skipCollection(@Nullable Field owner);

  public void skipString() {
    this.skipString(null);
  }

  public abstract void skipString(@Nullable Field owner);

  public void skipGuessingType() {
    this.skipGuessingType(null);
  }

  public abstract void skipGuessingType(@Nullable Field owner);

  public boolean skipComments(char marker, boolean reuse) {
    return this.skipComments(null, marker, reuse);
  }

  public abstract boolean skipComments(@Nullable Field owner, char marker, boolean reuse);

  public char readRawIgnoreEmptyAndNewLines() {
    return this.readRawIgnoreEmptyAndCharacter(AbstractReader.NEW_LINE);
  }

  public char readRawIgnoreEmptyAndCharacter(char marker) {
    char readMarker;
    do {
      readMarker = this.readRawIgnoreEmpty();
    } while (readMarker == marker);
    return readMarker;
  }

  public char readRawIgnoreEmpty() {
    char marker;
    int type;
    do {
      marker = this.readRaw();
      type = Character.getType(marker);
    } while (type == Character.SPACE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR || marker == '\t');
    return marker;
  }

  public char readRaw() {
    if (this.reuseBuffer) {
      this.reuseBuffer = false;
    } else {
      this.singleCharBuffer[0] = this.replayBuffer.isEmpty() ? this.readNormalizedSource() : this.replayBuffer.removeFirst();
      if (this.seekEnabled) {
        this.seekBuffer.addLast(this.singleCharBuffer[0]);
      }
    }

    return this.singleCharBuffer[0];
  }

  private char readNormalizedSource() {
    int marker = this.readSource();
    if (marker == '\r') {
      if (this.carriageType == CarriageType.LF) {
        throw new IllegalStateException("Caught a Carriage Return in LF mode");
      }
      int next = this.readSource();
      if (next == '\n') {
        if (this.carriageType == CarriageType.CR) {
          throw new IllegalStateException("Caught a Line Feed in CR mode");
        }
        this.carriageType = CarriageType.CRLF;
      } else {
        if (this.carriageType == CarriageType.CRLF) {
          throw new IllegalStateException("Expected a Line Feed after Carriage Return");
        }
        this.carriageType = CarriageType.CR;
        this.sourcePushback = next;
      }
      return AbstractReader.NEW_LINE;
    } else if (marker == '\n') {
      if (this.carriageType == CarriageType.CR) {
        throw new IllegalStateException("Unexpected Line Feed without Carriage Return");
      }
      if (this.carriageType == CarriageType.UNKNOWN) {
        this.carriageType = CarriageType.LF;
      }
    }
    return marker < 0 ? 0 : (char) marker;
  }

  private int readSource() {
    if (this.sourcePushback >= 0) {
      int result = this.sourcePushback;
      this.sourcePushback = -1;
      return result;
    }
    try {
      int result = this.reader.read();
      if (result == 0) {
        throw new IllegalStateException("NUL characters are not permitted in source text");
      }
      return result;
    } catch (IOException e) {
      throw new SerializableReadException(e);
    }
  }

  public void replaceSingleCharBuffer(char replacement) {
    this.singleCharBuffer[0] = replacement;
  }

  public void setReuseBuffer() {
    this.reuseBuffer = true;
  }

  public boolean isReuseBuffer() {
    return this.reuseBuffer;
  }

  public void setSeek() {
    this.seekBuffer.clear();
    this.seekEnabled = true;
  }

  public void setSeekFromMarker(char marker) {
    this.seekBuffer.clear();
    this.seekBuffer.addLast(marker);
    this.seekEnabled = true;
  }

  public void unsetSeek() {
    this.seekEnabled = false;
    this.reuseBuffer = false;
    while (!this.seekBuffer.isEmpty()) {
      this.replayBuffer.addFirst(this.seekBuffer.removeLast());
    }
  }

  public void clearSeek() {
    this.seekBuffer.clear();
    this.seekEnabled = false;
  }

  public boolean isBackupPreferred() {
    return this.backupPreferred;
  }

  protected void setBackupPreferred() {
    this.backupPreferred = true;
  }

  private enum CarriageType {

    UNKNOWN,
    CR,
    CRLF,
    LF
  }
}
