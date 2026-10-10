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

import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.elytrium.serializer.annotations.Serializer;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.placeholders.PlaceholderReplacer;
import net.elytrium.serializer.utils.GenericUtils;
import org.jetbrains.annotations.Nullable;

public class SerializerConfig {

  public static final SerializerConfig DEFAULT = new SerializerConfig.Builder().build();

  private final Map<Class<? extends PlaceholderReplacer<?, ?>>, PlaceholderReplacer<?, ?>> cachedReplacers = new HashMap<>();
  private final Map<Class<? extends ClassSerializer<?, ?>>, ClassSerializer<?, ?>> cachedSerializers = new HashMap<>();
  private final Map<Class<?>, PlaceholderReplacer<?, ?>> registeredReplacers;
  private final Map<Class<?>, ClassSerializer<?, ?>> registeredSerializers;
  private final String lineSeparator;
  private final String doubledLineSeparator;
  private final char[] lineSeparatorChars;
  private final NameStyle fieldNameStyle;
  private final NameStyle nodeNameStyle;
  private final boolean safeMode;
  private final boolean allowUnicode;
  private final boolean registerPlaceholdersForCollectionEntries;
  private final boolean logMissingFields;
  private final boolean backupOnErrors;
  private final int commentValueIndent;

  private SerializerConfig(Map<Class<?>, PlaceholderReplacer<?, ?>> registeredReplacers, Map<Class<?>, ClassSerializer<?, ?>> registeredSerializers,
      String lineSeparator,
      NameStyle fieldNameStyle, NameStyle nodeNameStyle,
      boolean safeMode, boolean allowUnicode, boolean registerPlaceholdersForCollectionEntries, boolean logMissingFields, boolean backupOnErrors,
      int commentValueIndent) {
    this.registeredReplacers = Map.copyOf(registeredReplacers);
    this.registeredSerializers = Map.copyOf(registeredSerializers);
    this.lineSeparator = lineSeparator;
    this.doubledLineSeparator = lineSeparator + lineSeparator;
    this.lineSeparatorChars = lineSeparator.toCharArray();
    this.fieldNameStyle = fieldNameStyle;
    this.nodeNameStyle = nodeNameStyle;
    this.safeMode = safeMode;
    this.allowUnicode = allowUnicode;
    this.registerPlaceholdersForCollectionEntries = registerPlaceholdersForCollectionEntries;
    this.logMissingFields = logMissingFields;
    this.backupOnErrors = backupOnErrors;
    this.commentValueIndent = commentValueIndent;
  }

  /**
   * Converts the class field name to the config node field format.
   */
  public String toNodeName(String field) {
    return this.toNodeName(field, null, null);
  }

  /**
   * Converts the class field name to the config node field format.
   */
  public String toNodeName(String field, NameStyle overriddenFieldNameStyle, NameStyle overriddenNodeNameStyle) {
    if (overriddenFieldNameStyle == null) {
      overriddenFieldNameStyle = this.fieldNameStyle;
    }

    if (overriddenNodeNameStyle == null) {
      overriddenNodeNameStyle = this.nodeNameStyle;
    }

    return !field.isEmpty() && Character.isDigit(field.charAt(0))
        ? this.toNodeName('"' + field + '"')
        : overriddenFieldNameStyle == overriddenNodeNameStyle ? field : overriddenNodeNameStyle.fromMacroCase(overriddenFieldNameStyle.toMacroCase(field));
  }

  /**
   * Converts the config node field to the class field name format.
   */
  public String toFieldName(String field) {
    return this.toFieldName(field, null, null);
  }

  /**
   * Converts the config node field to the class field name format.
   */
  public String toFieldName(String field, NameStyle overriddenFieldNameStyle, NameStyle overriddenNodeNameStyle) {
    if (overriddenFieldNameStyle == null) {
      overriddenFieldNameStyle = this.fieldNameStyle;
    }

    if (overriddenNodeNameStyle == null) {
      overriddenNodeNameStyle = this.nodeNameStyle;
    }

    return overriddenFieldNameStyle == overriddenNodeNameStyle ? field : overriddenFieldNameStyle.fromMacroCase(overriddenNodeNameStyle.toMacroCase(field));
  }

  public synchronized PlaceholderReplacer<?, ?> getAndCacheReplacer(Class<? extends PlaceholderReplacer<?, ?>> clazz) throws ReflectiveOperationException {
    PlaceholderReplacer<?, ?> replacer = this.cachedReplacers.get(clazz);
    if (replacer == null) {
      for (PlaceholderReplacer<?, ?> registered : this.registeredReplacers.values()) {
        if (registered.getClass() == clazz) {
          replacer = registered;
          break;
        }
      }
    }

    if (replacer == null) {
      Constructor<? extends PlaceholderReplacer<?, ?>> constructor = clazz.getDeclaredConstructor();
      constructor.setAccessible(true);
      replacer = constructor.newInstance();
    }
    this.cachedReplacers.put(clazz, replacer);
    return replacer;
  }

  @SuppressWarnings("unchecked")
  public synchronized <T, F> ClassSerializer<T, F> getAndCacheSerializer(Serializer serializer) throws ReflectiveOperationException {
    Class<? extends ClassSerializer<?, ?>> clazz = serializer.value();
    var configSerializer = (ClassSerializer<T, F>) this.cachedSerializers.get(clazz);
    if (configSerializer == null) {
      for (ClassSerializer<?, ?> registered : this.registeredSerializers.values()) {
        if (registered.getClass() == clazz) {
          configSerializer = (ClassSerializer<T, F>) registered;
          break;
        }
      }
    }

    if (configSerializer == null) {
      Constructor<? extends ClassSerializer<?, ?>> constructor = clazz.getDeclaredConstructor();
      constructor.setAccessible(true);
      configSerializer = (ClassSerializer<T, F>) constructor.newInstance();
    }
    this.cachedSerializers.put(clazz, configSerializer);
    return configSerializer;
  }

  @Nullable
  @SuppressWarnings("unchecked")
  public <T, P> PlaceholderReplacer<T, P> getRegisteredReplacer(Class<?> clazz) {
    return (PlaceholderReplacer<T, P>) SerializerConfig.findRegistered(this.registeredReplacers, clazz);
  }

  @Nullable
  @SuppressWarnings("unchecked")
  public <T, F> ClassSerializer<T, F> getRegisteredSerializer(Class<?> clazz) {
    if (clazz != null) {
      ClassSerializer<?, ?> exact = this.registeredSerializers.get(clazz);
      if (exact != null) {
        return (ClassSerializer<T, F>) exact;
      }
      Class<?> counterpart = SerializerConfig.primitiveCounterpart(clazz);
      if (counterpart != null) {
        ClassSerializer<?, ?> equivalent = this.registeredSerializers.get(counterpart);
        if (equivalent != null) {
          return (ClassSerializer<T, F>) equivalent;
        }
        if (clazz.isPrimitive()) {
          clazz = counterpart;
        }
      }
    }
    return (ClassSerializer<T, F>) SerializerConfig.findRegistered(this.registeredSerializers, clazz);
  }

  @Nullable
  private static Class<?> primitiveCounterpart(Class<?> clazz) {
    if (clazz == int.class || clazz == Integer.class) {
      return clazz == int.class ? Integer.class : int.class;
    } else if (clazz == long.class || clazz == Long.class) {
      return clazz == long.class ? Long.class : long.class;
    } else if (clazz == short.class || clazz == Short.class) {
      return clazz == short.class ? Short.class : short.class;
    } else if (clazz == byte.class || clazz == Byte.class) {
      return clazz == byte.class ? Byte.class : byte.class;
    } else if (clazz == float.class || clazz == Float.class) {
      return clazz == float.class ? Float.class : float.class;
    } else if (clazz == double.class || clazz == Double.class) {
      return clazz == double.class ? Double.class : double.class;
    } else if (clazz == boolean.class || clazz == Boolean.class) {
      return clazz == boolean.class ? Boolean.class : boolean.class;
    } else if (clazz == char.class || clazz == Character.class) {
      return clazz == char.class ? Character.class : char.class;
    }
    return null;
  }

  private static <T> T findRegistered(Map<Class<?>, T> registered, Class<?> clazz) {
    var visited = new HashSet<Class<?>>();
    var pending = new ArrayDeque<Class<?>>();
    if (clazz != null) {
      pending.add(clazz);
    }
    while (!pending.isEmpty()) {
      Class<?> current = pending.remove();
      if (!visited.add(current)) {
        continue;
      }
      if (current == Object.class) {
        continue;
      }
      T result = registered.get(current);
      if (result != null) {
        return result;
      }
      for (Class<?> implemented : current.getInterfaces()) {
        pending.add(implemented);
      }
      if (current.getSuperclass() != null) {
        pending.add(current.getSuperclass());
      }
    }
    return registered.get(Object.class);
  }

  public synchronized int getRegisteredSerializers() {
    return this.cachedSerializers.size() + this.registeredSerializers.size();
  }

  public String getLineSeparator() {
    return this.lineSeparator;
  }


  public String getDoubledLineSeparator() {
    return this.doubledLineSeparator;
  }

  public char[] getLineSeparatorChars() {
    return this.lineSeparatorChars;
  }

  public boolean isSafeMode() {
    return this.safeMode;
  }

  public boolean isAllowUnicode() {
    return this.allowUnicode;
  }

  public boolean isRegisterPlaceholdersForCollectionEntries() {
    return this.registerPlaceholdersForCollectionEntries;
  }

  public boolean isLogMissingFields() {
    return this.logMissingFields;
  }

  public boolean isBackupOnErrors() {
    return this.backupOnErrors;
  }

  public int getCommentValueIndent() {
    return this.commentValueIndent;
  }

  public static class Builder {

    private final Map<Class<?>, PlaceholderReplacer<?, ?>> registeredReplacers = new HashMap<>();
    private final Map<Class<?>, ClassSerializer<?, ?>> registeredSerializers = new HashMap<>();

    private String lineSeparator = System.lineSeparator();
    private NameStyle fieldNameStyle = NameStyle.CAMEL_CASE;
    private NameStyle nodeNameStyle = NameStyle.KEBAB_CASE;
    /**
     * @see SerializerConfig.Builder#setSafeMode(boolean)
     */
    private boolean safeMode = false;
    /**
     * @see SerializerConfig.Builder#setAllowUnicode(boolean)
     */
    private boolean allowUnicode = false;
    private boolean registerPlaceholdersForCollectionEntries = true;
    private boolean logMissingFields = true;
    private boolean backupOnErrors = true;
    private int commentValueIndent = 0;

    public Builder registerReplacer(Collection<PlaceholderReplacer<?, ?>> replacers) {
      replacers.forEach(this::registerReplacer);
      return this;
    }

    public Builder registerReplacer(PlaceholderReplacer<?, ?> serializer) {
      Class<?> valueClass = GenericUtils.unwrapClassParameterizedType(GenericUtils.getParameterType(PlaceholderReplacer.class, serializer.getClass(), 0));
      return this.registerReplacer(valueClass, serializer);
    }

    public Builder registerReplacer(Map<Class<?>, PlaceholderReplacer<?, ?>> replacers) {
      replacers.forEach(this::registerReplacer);
      return this;
    }

    public Builder registerReplacer(Class<?> valueClass, PlaceholderReplacer<?, ?> serializer) {
      this.registeredReplacers.put(valueClass, serializer);
      return this;
    }

    public Builder registerSerializer(Collection<ClassSerializer<?, ?>> serializers) {
      serializers.forEach(this::registerSerializer);
      return this;
    }

    public Builder registerSerializer(ClassSerializer<?, ?> serializer) {
      this.registeredSerializers.put(serializer.getToClass(), serializer);
      return this;
    }

    public Builder setFieldNameStyle(NameStyle fieldNameStyle) {
      this.fieldNameStyle = fieldNameStyle;
      return this;
    }

    public Builder setNodeNameStyle(NameStyle nodeNameStyle) {
      this.nodeNameStyle = nodeNameStyle;
      return this;
    }

    /**
     * When true, bad numeric or decimal field value will be interpreted as 0 or 0.0
     */
    public Builder setSafeMode(boolean safeMode) {
      this.safeMode = safeMode;
      return this;
    }

    /**
     * When false, most of unprintable characters will be written in escaped format like {@literal \}u1234{@literal \}u4321
     */
    public Builder setAllowUnicode(boolean allowUnicode) {
      this.allowUnicode = allowUnicode;
      return this;
    }

    public Builder setLineSeparator(String lineSeparator) {
      this.lineSeparator = lineSeparator;
      return this;
    }

    public Builder setRegisterPlaceholdersForCollectionEntries(boolean registerPlaceholdersForCollectionEntries) {
      this.registerPlaceholdersForCollectionEntries = registerPlaceholdersForCollectionEntries;
      return this;
    }

    public Builder setLogMissingFields(boolean logMissingFields) {
      this.logMissingFields = logMissingFields;
      return this;
    }

    public Builder setBackupOnErrors(boolean backupOnErrors) {
      this.backupOnErrors = backupOnErrors;
      return this;
    }

    public Builder setCommentValueIndent(int commentValueIndent) {
      this.commentValueIndent = commentValueIndent;
      return this;
    }

    public SerializerConfig build() {
      return new SerializerConfig(
          this.registeredReplacers,
          this.registeredSerializers,
          this.lineSeparator,
          this.fieldNameStyle,
          this.nodeNameStyle,
          this.safeMode,
          this.allowUnicode,
          this.registerPlaceholdersForCollectionEntries,
          this.logMissingFields,
          this.backupOnErrors,
          this.commentValueIndent
      );
    }
  }
}
