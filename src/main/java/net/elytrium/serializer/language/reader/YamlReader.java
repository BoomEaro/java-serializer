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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.elytrium.serializer.SerializerConfig;
import net.elytrium.serializer.annotations.FallbackNodeNames;
import net.elytrium.serializer.annotations.Final;
import net.elytrium.serializer.annotations.OverrideNameStyle;
import net.elytrium.serializer.annotations.RegisterPlaceholders;
import net.elytrium.serializer.annotations.Transient;
import net.elytrium.serializer.language.object.YamlSerializable;
import net.elytrium.serializer.language.writer.YamlWriter;
import net.elytrium.serializer.placeholders.DefaultPlaceholderReplacer;
import net.elytrium.serializer.placeholders.PlaceholderReplacer;
import net.elytrium.serializer.placeholders.Placeholders;
import net.elytrium.serializer.utils.GenericUtils;
import org.jetbrains.annotations.Nullable;

public class YamlReader extends AbstractReader {

  private static final Logger LOGGER = Logger.getLogger(YamlReader.class.getName());

  private final YamlSerializable yamlSerializable;

  private int currentIndent;
  private int seekIndent;
  private int nodeIndent;
  private int newLineIndent;
  private boolean tempRestoreNewLine;
  private boolean bracketOpened;
  private boolean startOfFile = true;

  private StringBuilder spacesBuffer;

  public YamlReader(BufferedReader reader, SerializerConfig config) {
    super(config, reader);
    this.yamlSerializable = null;
  }

  public YamlReader(BufferedReader reader, SerializerConfig config, YamlSerializable serializable) {
    super(config, reader);
    this.yamlSerializable = serializable;
  }

  public YamlReader(BufferedReader reader) {
    super(reader);
    this.yamlSerializable = null;
  }

  @Override
  public void readSerializableObject(@Nullable Field owner, Object holder, Class<?> clazz) {
    synchronized (this) {
      boolean document = this.startOfFile;
      this.unsetTempRestoreNewLine();
      boolean previousBracketOpened = this.bracketOpened;
      int previousNodeIndent = this.nodeIndent;
      try {
        char marker = this.readContentMarker(owner);
        boolean flowObject = marker == '{';
        this.bracketOpened = flowObject;
        if (flowObject) {
          marker = this.readFlowMarker(owner);
        }

        Field[] nodes = clazz.getDeclaredFields();
        // Register initial values and make nodeName->field map.
        Map<String, Field> nodeFieldMap = new HashMap<>();
        for (Field node : nodes) {
          int modifiers = node.getModifiers();
          if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
              || node.getAnnotation(Transient.class) != null || node.getType().getAnnotation(Transient.class) != null) {
            continue;
          }
          try {
            node.setAccessible(true);
          } catch (Exception e) {
            continue;
          }

          Object value = node.get(holder);
          this.updatePlaceholders(node, value); // Updating placeholders here in case if on reload field was written and not read yet.

          OverrideNameStyle overrideNameStyle = node.getAnnotation(OverrideNameStyle.class);
          if (overrideNameStyle == null) {
            overrideNameStyle = node.getType().getAnnotation(OverrideNameStyle.class);
          }

          String nodeName = overrideNameStyle == null ? this.config.toNodeName(node.getName())
              : this.config.toNodeName(node.getName(), overrideNameStyle.field(), overrideNameStyle.node());
          this.registerNodeField(nodeFieldMap, nodeName, node);

          FallbackNodeNames fallbackNodeNames = node.getAnnotation(FallbackNodeNames.class);
          if (fallbackNodeNames != null) {
            for (String fallbackNodeName : fallbackNodeNames.value()) {
              this.registerNodeField(nodeFieldMap, fallbackNodeName, node);
            }
          }
        }

        int correctIndent = this.currentIndent;
        while (flowObject ? marker != '}' : marker != '\0' && correctIndent == this.currentIndent) {
          String nodeName = this.readNodeNameFromMarker(owner, marker);
          try {
            Field node = nodeFieldMap.get(nodeName);
            if (node == null) {
              this.skipGuessingType(owner);
              this.setBackupPreferred();
              if (this.config.isLogMissingFields()) {
                YamlReader.LOGGER.log(Level.WARNING, "Skipping node " + nodeName + " due to missing field");
              }
            } else {
              int modifiers = node.getModifiers();
              if (!Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers) && !Modifier.isTransient(modifiers)
                  && node.getAnnotation(Final.class) == null && node.getType().getAnnotation(Final.class) == null
                  && node.getAnnotation(Transient.class) == null && node.getType().getAnnotation(Transient.class) == null) {
                this.updatePlaceholders(node, this.readNode(holder, node));
              } else {
                this.skipNode(node);
              }
            }
          } catch (ReflectiveOperationException e) {
            this.skipGuessingType(owner);
            this.setBackupPreferred();
            if (this.config.isLogMissingFields()) {
              YamlReader.LOGGER.log(Level.WARNING, "Skipping node " + nodeName + " due to exception caught", e);
            }
          }

          marker = flowObject ? this.readFlowSeparator(owner, '}') : this.readContentMarker(owner);
        }

        if (!flowObject) {
          this.setReuseBuffer();
        }
        if (document) {
          this.readDocumentEnd(owner);
        }
      } catch (ReflectiveOperationException e) {
        throw new RuntimeException(e);
      } finally {
        this.bracketOpened = previousBracketOpened;
        this.nodeIndent = previousNodeIndent;
      }
    }
  }

  private void registerNodeField(Map<String, Field> fields, String name, Field node) {
    Field previous = fields.putIfAbsent(name, node);
    if (previous != null && !previous.equals(node)) {
      throw new IllegalStateException("Conflicting node name '" + name + "' for fields " + previous + " and " + node);
    }
  }

  private void updatePlaceholders(Field node, Object value) throws ReflectiveOperationException {
    int modifiers = node.getModifiers();
    if (value == null || Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
        || node.getAnnotation(Transient.class) != null || node.getType().getAnnotation(Transient.class) != null) {
      return;
    }
    RegisterPlaceholders placeholders = node.getAnnotation(RegisterPlaceholders.class);
    if (placeholders == null) {
      placeholders = node.getType().getAnnotation(RegisterPlaceholders.class);
    }

    if (placeholders != null) {
      PlaceholderReplacer<?, ?> replacer = null;
      if (placeholders.replacer() == DefaultPlaceholderReplacer.class) {
        replacer = value instanceof Collection<?>
            ? this.config.getRegisteredReplacer(GenericUtils.unwrapClassParameterizedType(GenericUtils.getParameterType(Collection.class, node.getGenericType(), 0)))
            : this.config.getRegisteredReplacer(node.getType());
      }

      if (replacer == null) {
        replacer = this.config.getAndCacheReplacer(placeholders.replacer());
      }

      if (this.config.isRegisterPlaceholdersForCollectionEntries() && value instanceof Collection<?> collection) {
        for (Object entry : collection) {
          if (entry != null) {
            this.afterCommit(Placeholders.prepareRegistration(entry, replacer, placeholders.wrapWithBraces(), placeholders.value()));
          }
        }
      }

      this.afterCommit(Placeholders.prepareRegistration(value, replacer, placeholders.wrapWithBraces(), placeholders.value()));
    }
  }

  private void saveStringStyle(Field owner, YamlWriter.StringStyle style) {
    this.afterCommit(() -> this.yamlSerializable.saveStringStyle(owner, style));
  }

  @Override
  public String readNodeName(@Nullable Field owner) {
    synchronized (this) {
      char marker = this.readContentMarker(owner);
      return marker == '\0' ? null : this.readNodeNameFromMarker(owner, marker);
    }
  }

  @Override
  public void readBeginSerializableObject(@Nullable Field owner) {
    synchronized (this) {
      this.bracketOpened = this.skipChar('{');
    }
  }

  @Override
  public void readSerializableObjectEntryJoin(@Nullable Field owner) {
    synchronized (this) {
      this.skipChar(',');
    }
  }

  @Override
  public boolean readEndSerializableObject(@Nullable Field owner) {
    synchronized (this) {
      return this.skipChar('}');
    }
  }

  @Override
  public Object readGuessingType(@Nullable Field owner) {
    synchronized (this) {
      return this.readGuessingTypeFromMarker(owner, this.readRawIgnoreEmpty());
    }
  }

  @Override
  public <C extends Map<Object, Object>> C readMap(@Nullable Field owner, C result, Type keyType, Type valueType) {
    boolean startOfFile = this.startOfFile;
    synchronized (this) {
      char marker = this.readRawIgnoreEmpty();
      if (startOfFile) {
        this.setTempRestoreNewLine();
      }

      C value = this.readMapFromMarker(owner, result, keyType, valueType, marker);
      if (startOfFile) {
        this.readDocumentEnd(owner);
      }
      return value;
    }
  }

  @Override
  public <C extends Collection<Object>> C readCollection(@Nullable Field owner, C result, Type type) {
    synchronized (this) {
      return this.readCollectionFromMarker(owner, result, type, this.readRawIgnoreEmpty());
    }
  }

  @Override
  public Character readCharacter(@Nullable Field owner) {
    synchronized (this) {
      return this.readCharacterFromMarker(owner, this.readRawIgnoreEmpty());
    }
  }

  @Override
  public Boolean readBoolean(@Nullable Field owner) {
    synchronized (this) {
      String value = this.readString(owner);
      if (value == null) {
        return null;
      }
      if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
        throw new IllegalStateException("Invalid boolean value: " + value);
      }
      return Boolean.valueOf(value);
    }
  }

  @Override
  public Double readDouble(@Nullable Field owner) throws NumberFormatException {
    synchronized (this) {
      String value = this.readString(owner);
      return value == null ? null : Double.valueOf(value);
    }
  }

  @Override
  public Long readLong(@Nullable Field owner) throws NumberFormatException {
    synchronized (this) {
      String value = this.readString(owner);
      return value == null ? null : Long.valueOf(value);
    }
  }

  @Override
  public String readString(@Nullable Field owner) {
    synchronized (this) {
      return this.readStringFromMarker(owner, this.readRawIgnoreEmpty(), false);
    }
  }

  private String readNodeNameFromMarker(@Nullable Field owner, char marker) {
    if (this.bracketOpened && marker == ',') {
      throw new IllegalStateException("Missing entry in flow mapping");
    }
    synchronized (this) {
      this.nodeIndent = this.currentIndent;
    }

    String nodeName;
    if (marker == '"' || marker == '\'') {
      nodeName = this.readStringFromMarker(owner, marker, true);
    } else {
      StringBuilder result = new StringBuilder(12);
      while (true) {
        while (marker != ':') {
          if (this.isEndMarker(marker)) {
            throw new IllegalStateException("Got a new line in node name: " + result);
          }

          result.append(marker);
          marker = this.readRaw();
        }

        marker = this.readRaw();
        if (Character.isWhitespace(marker) || marker == '\0' || this.isFlowDelimiter(marker)) {
          this.setReuseBuffer();
          break;
        }

        result.append(':');
      }

      nodeName = result.toString().stripTrailing();
    }

    return nodeName;
  }

  private Object readGuessingTypeFromMarker(@Nullable Field owner, char marker) {
    if (this.skipComments(owner, marker, false)) {
      marker = AbstractReader.NEW_LINE;
    }
    return switch (marker) {
      case '\0' -> null;
      case AbstractReader.NEW_LINE -> {
        char nextMarker = this.readContentMarker(owner);
        if (nextMarker == '-' && this.currentIndent >= this.nodeIndent) {
          this.setSeek();
          char following = this.readRaw();
          this.unsetSeek();
          if (following == '\0' || Character.isWhitespace(following)) {
            yield this.readListFromMarker(owner, nextMarker);
          }
        }
        if (nextMarker == '\0' || this.currentIndent <= this.nodeIndent) {
          this.setReuseBuffer();
          yield null;
        }
        this.setTempRestoreNewLine();
        try {
          yield this.readGuessingTypeFromMarker(owner, nextMarker);
        } finally {
          this.unsetTempRestoreNewLine();
        }
      }
      case '-' -> {
        this.setSeek();
        char nextMarker = this.readRaw();
        this.unsetSeek();
        if (nextMarker == '\0' || Character.isWhitespace(nextMarker)) {
          yield this.readListFromMarker(owner, marker);
        }
        yield this.readPlainValue(owner, marker);
      }
      case '[' -> this.readListFromMarker(owner, marker);
      case '{' -> this.readMapFromMarker(owner, marker);
      case '"', '\'' -> this.readQuotedValueOrMapping(owner, marker);
      case '>', '|' -> this.readStringFromMarker(owner, marker, false);
      default -> this.readPlainValue(owner, marker);
    };
  }

  private Object readQuotedValueOrMapping(@Nullable Field owner, char marker) {
    if (!this.bracketOpened) {
      this.setSeekFromMarker(marker);
      this.readQuotedString(marker, false, new StringBuilder());
      char following = this.readRawIgnoreEmpty();
      this.unsetSeek();
      marker = this.readRaw();
      if (following == ':') {
        this.setTempRestoreNewLine();
        return this.readMapFromMarker(owner, marker);
      }
    }
    return this.readStringFromMarker(owner, marker, false);
  }

  private Object readPlainValue(@Nullable Field owner, char marker) {
    this.setSeekFromMarker(marker);
    String string = this.readStringFromMarker(owner, marker, false, this.tempRestoreNewLine && !this.bracketOpened);
    if (string == null) {
      this.clearSeek();
      return null;
    }

    if (string.endsWith(":") || string.contains(": ") || string.contains(":\t")) {
      this.unsetSeek();
      this.setTempRestoreNewLine();
      return this.readMapFromMarker(owner, this.readRawIgnoreEmpty());
    } else {
      this.clearSeek();
      return this.resolvePlainScalar(string);
    }
  }

  private Object resolvePlainScalar(String string) {
    if (string == null) {
      return null;
    }
    if (string.equalsIgnoreCase("true") || string.equalsIgnoreCase("false")) {
      return Boolean.valueOf(string);
    }
    try {
      try {
        return Long.parseLong(string);
      } catch (NumberFormatException e) {
        return Double.parseDouble(string);
      }
    } catch (NumberFormatException e) {
      return string;
    }
  }

  private Map<Object, Object> readMapFromMarker(@Nullable Field owner, char marker) {
    return this.readMapFromMarker(owner, new LinkedHashMap<>(), Object.class, Object.class, marker);
  }

  @SuppressWarnings("DuplicatedCode")
  private <C extends Map<Object, Object>> C readMapFromMarker(@Nullable Field owner, C result, Type keyType, Type valueType, char marker) {
    if (this.skipComments(owner, marker, false)) { // TODO сделать чтобы map:#comm не читалось
      marker = AbstractReader.NEW_LINE;
    }

    if (marker == '\0' || this.isNullSkippedFromMarker(marker)) {
      this.unsetTempRestoreNewLine();
      return null;
    }
    int parentIndent = this.nodeIndent;
    boolean restored = this.tempRestoreNewLine;
    this.unsetTempRestoreNewLine();
    char nextMarker;
    if (restored && marker != '{' && marker != AbstractReader.NEW_LINE) {
      nextMarker = marker;
      marker = AbstractReader.NEW_LINE;
    } else {
      nextMarker = marker == '{' ? this.readFlowMarker(owner) : this.readContentMarker(owner);
    }
    if (!restored && marker == AbstractReader.NEW_LINE && (nextMarker == '\0' || this.currentIndent <= parentIndent)) {
      this.setReuseBuffer();
      return null;
    }
    if (marker == AbstractReader.NEW_LINE && nextMarker == '{') {
      marker = nextMarker;
      nextMarker = this.readFlowMarker(owner);
    }

    boolean previousBracketOpened = this.bracketOpened;
    switch (marker) {
      case '{' -> {
        this.bracketOpened = true;
        while (nextMarker != '}') {
          this.readMapEntry(owner, keyType, valueType, this.readNodeNameFromMarker(null, nextMarker), nextMarker == '"' || nextMarker == '\'', result);
          nextMarker = this.readFlowSeparator(owner, '}');
        }

        this.bracketOpened = previousBracketOpened;
      }
      case AbstractReader.NEW_LINE -> {
        this.bracketOpened = false;
        int correctIndent = this.currentIndent;
        while (nextMarker != '\0' && correctIndent == this.currentIndent) {
          this.readMapEntry(owner, keyType, valueType, this.readNodeNameFromMarker(null, nextMarker), nextMarker == '"' || nextMarker == '\'', result);
          nextMarker = this.readContentMarker(owner);
        }

        this.setReuseBuffer();
        this.bracketOpened = previousBracketOpened;
      }
      default -> {
        if (this.isNullSkippedFromMarker(marker)) {
          return null;
        } else {
          throw new IllegalStateException("Got unknown marker when reading map: " + marker);
        }
      }
    }

    this.nodeIndent = parentIndent;
    return result;
  }

  private void readMapEntry(@Nullable Field owner, Type keyType, Type valueType, String nodeName, boolean quoted, Map<Object, Object> result) {
    Object key;
    if (!quoted && nodeName.equals("null")) {
      key = null;
    } else if (keyType == Object.class && this.config.getRegisteredSerializer(Object.class) == null) {
      key = quoted ? nodeName : this.resolvePlainScalar(nodeName);
    } else {
      key = this.deserializeMapKey(nodeName, keyType, quoted ? nodeName : this.resolvePlainScalar(nodeName));
    }
    result.put(key, this.readByType0(owner, valueType));
  }

  private List<Object> readListFromMarker(@Nullable Field owner, char marker) {
    return this.readCollectionFromMarker(owner, new ArrayList<>(), Object.class, marker);
  }

  private <C extends Collection<Object>> C readCollectionFromMarker(@Nullable Field owner, C result, Type type, char marker) {
    if (this.skipComments(owner, marker, false)) { // TODO сделать чтобы collection:#comm не читалось
      marker = AbstractReader.NEW_LINE;
    }

    this.unsetTempRestoreNewLine();
    switch (marker) {
      case '\0': {
        return null;
      }
      case '[': {
        boolean previousBracketOpened = this.bracketOpened;
        this.bracketOpened = true;
        char nextMarker = this.readFlowMarker(owner);
        while (nextMarker != ']') {
          if (nextMarker == ',') {
            throw new IllegalStateException("Missing entry in flow sequence");
          }
          this.setReuseBuffer();
          result.add(this.readByType0(owner, type));
          nextMarker = this.readFlowSeparator(owner, ']');
        }

        this.bracketOpened = previousBracketOpened;
        break;
      }
      case AbstractReader.NEW_LINE: {
        char nextMarker = this.readContentMarker(owner);
        if (nextMarker != '-') {
          if (nextMarker == '\0' || this.currentIndent <= this.nodeIndent) {
            this.setReuseBuffer();
            return null;
          }
          throw new IllegalStateException("Got unknown marker when reading list: " + nextMarker);
        }
      } // Got '-' after newline, fall through here.
      case '-': {
        int parentIndent = this.nodeIndent;
        char nextMarker = '-';
        int correctIndent = this.currentIndent;
        while (nextMarker == '-' && correctIndent == this.currentIndent) {
          this.nodeIndent = correctIndent;
          this.setTempRestoreNewLine();
          result.add(this.readBlockSequenceEntry(type, correctIndent));
          this.unsetTempRestoreNewLine();
          nextMarker = this.readContentMarker(owner);
        }

        this.nodeIndent = parentIndent;
        this.setReuseBuffer();
        break;
      }
      default: {
        if (this.isNullSkippedFromMarker(marker)) {
          return null;
        } else {
          throw new IllegalStateException("Got unknown marker when reading list: " + marker);
        }
      }
    }

    return result;
  }

  private Object readBlockSequenceEntry(Type type, int indent) {
    this.setSeek();
    char marker = this.readRawIgnoreEmpty();
    if (this.skipComments(null, marker, false)) {
      marker = AbstractReader.NEW_LINE;
    }
    if (marker == AbstractReader.NEW_LINE) {
      char nextMarker = this.readContentMarker(null);
      if (nextMarker == '\0' || this.currentIndent <= indent) {
        this.clearSeek();
        this.setReuseBuffer();
        return null;
      }
    } else if (marker == '\0') {
      this.clearSeek();
      return null;
    }
    this.unsetSeek();
    return this.readByType0(null, type);
  }

  private Object readByType0(@Nullable Field owner, Type type) {
    return this.readByType(null, type);
  }

  private Character readCharacterFromMarker(@Nullable Field owner, char marker) {
    String value = this.readStringFromMarker(owner, marker, false);
    if (value == null) {
      return null;
    }
    if (value.length() != 1) {
      throw new IllegalStateException("Expected exactly one character, got: " + value);
    }
    return value.charAt(0);
  }

  private int readEscapedCharacter() {
    synchronized (this) {
      char marker;
      return switch (marker = this.readRaw()) {
        case '0' -> '\0';
        case 'a' -> '\u0007';
        case 'b' -> '\b';
        case 't' -> '\t';
        case 'n' -> '\n';
        case 'v' -> '\u000B';
        case 'f' -> '\f';
        case 'r' -> '\r';
        case 'e' -> '\u001B';
        case ' ' -> ' ';
        case '"' -> '\"';
        case '\\' -> '\\';
        case '/' -> '/';
        case 'N' -> '\u0085';
        case '_' -> '\u00A0';
        case 'L' -> '\u2028';
        case 'P' -> '\u2029';
        case 'x' -> this.readHexChar(2);
        case 'u' -> this.readHexChar(4);
        case 'U' -> this.readHexChar(8);
        default -> throw new IllegalStateException("Invalid escape character: \\" + marker);
      };
    }
  }

  private int readHexChar(int size) {
    StringBuilder hex = new StringBuilder(size);
    for (int i = 0; i < size; ++i) {
      char character = this.readRaw();
      if (this.isEndMarker(character)) {
        throw new IllegalStateException("Got new line while reading hex char");
      }

      hex.append(character);
    }

    return Integer.parseInt(hex.toString(), 16);
  }

  private String readStringFromMarker(@Nullable Field owner, char marker, boolean nodeName) {
    return this.readStringFromMarker(owner, marker, nodeName, false);
  }

  private String readStringFromMarker(@Nullable Field owner, char marker, boolean nodeName, boolean mappingCandidate) {
    if (!nodeName && this.isFlowDelimiter(marker)) {
      this.setReuseBuffer();
      return null;
    }
    StringBuilder result = new StringBuilder();
    switch (marker) {
      case '"' -> {
        if (owner != null && !nodeName && this.yamlSerializable != null) {
          this.saveStringStyle(owner, YamlWriter.StringStyle.DOUBLE_QUOTED);
        }

        this.readQuotedString('"', nodeName, result);

        if (nodeName) {
          marker = this.readRawIgnoreEmpty();
          if (marker != ':') {
            throw new IllegalStateException("Got illegal marker when reading node name: " + marker + " for " + result);
          }
        } else {
          this.readQuotedValueEnd(owner);
        }
      }
      case '\'' -> {
        if (owner != null && !nodeName && this.yamlSerializable != null) {
          this.saveStringStyle(owner, YamlWriter.StringStyle.SINGLE_QUOTED);
        }

        this.readQuotedString('\'', nodeName, result);

        if (nodeName) {
          marker = this.readRawIgnoreEmpty();
          if (marker != ':') {
            throw new IllegalStateException("Got illegal marker when reading node name: " + marker + " for " + result);
          }
        } else {
          this.readQuotedValueEnd(owner);
        }
      }
      default -> {
        if (marker == '#') {
          this.skipComments(owner, marker, false);
          break;
        }

        if (!nodeName && (marker == '|' || marker == '>')) {
          this.readMultilineStringFromMarker(owner, marker, result);
          break;
        }

        if (this.spacesBuffer != null && this.spacesBuffer.length() != 0) {
          this.spacesBuffer.setLength(0);
        }

        // See YamlReader#skipComments(char, boolean) for details about Character.isWhitespace(char) and YamlReader#skipComments(char, true/*!!*/).
        while (nodeName
            ? (marker != ':')
            : (!this.isEndMarker(marker) && !this.isFlowDelimiter(marker)
                && (marker != '/' || !this.skipComments(owner, marker, false))
                && (!Character.isWhitespace(marker) || !this.skipComments(owner, this.readRaw(), true)))) {
          if (nodeName && this.isEndMarker(marker)) {
            throw new IllegalStateException("Got a new line in node name: " + result);
          }
          if (!nodeName && !mappingCandidate && marker == ':') {
            char following = this.readRaw();
            this.setReuseBuffer();
            if (following == ' ' || following == '\t' || this.isEndMarker(following) || this.bracketOpened
                && (this.isFlowDelimiter(following) || following == '[' || following == '{')) {
              throw new IllegalStateException("Mapping separator is not allowed in a plain value: " + result + ":");
            }
          }

          if (Character.isWhitespace(marker)) {
            if (this.spacesBuffer == null) {
              this.spacesBuffer = new StringBuilder(4);
            }

            this.spacesBuffer.append(marker);
          } else {
            if (this.spacesBuffer != null && this.spacesBuffer.length() != 0) {
              result.append(this.spacesBuffer);
              this.spacesBuffer.setLength(0);
            }

            result.append(marker);
          }

          marker = this.readRaw();
        }

        if (!nodeName) {
          if (this.isFlowDelimiter(marker)) {
            this.setReuseBuffer();
          }

          String string = result.toString();
          if (string.equals("null")) {
            return null;
          }

          if (this.yamlSerializable != null && owner != null) {
            this.saveStringStyle(owner, YamlWriter.StringStyle.NOT_QUOTED);
          }

          return string;
        }
      }
    }

    return result.toString();
  }

  private void readQuotedString(char quote, boolean nodeName, StringBuilder result) {
    StringBuilder whitespace = new StringBuilder();
    StringBuilder lineBreaks = new StringBuilder();
    while (true) {
      char marker = this.readQuotedMarker();
      if (marker == AbstractReader.NEW_LINE || marker == '\u2028' || marker == '\u2029') {
        if (nodeName) {
          throw new IllegalStateException("Got a new line in node name: " + result);
        }
        whitespace.setLength(0);
        lineBreaks.append(marker);
        this.readQuotedIndentation();
        this.setReuseBuffer();
        continue;
      }
      if (lineBreaks.length() != 0) {
        if (lineBreaks.charAt(0) != AbstractReader.NEW_LINE) {
          result.append(lineBreaks);
        } else if (lineBreaks.length() == 1) {
          result.append(' ');
        } else {
          result.append(lineBreaks, 1, lineBreaks.length());
        }
      }
      lineBreaks.setLength(0);
      if (marker == quote) {
        if (quote == '\'' && this.readRaw() == '\'') {
          result.append(whitespace).append(quote);
          whitespace.setLength(0);
          continue;
        }
        if (quote == '\'') {
          this.setReuseBuffer();
        }
        result.append(whitespace);
        return;
      }
      if (marker == ' ' || marker == '\t') {
        whitespace.append(marker);
        continue;
      }
      result.append(whitespace);
      whitespace.setLength(0);
      if (quote == '"' && marker == '\\') {
        char escaped = this.readQuotedMarker();
        if (escaped == AbstractReader.NEW_LINE) {
          if (nodeName) {
            throw new IllegalStateException("Got a new line in node name: " + result);
          }
          while ((escaped = this.readQuotedIndentation()) == AbstractReader.NEW_LINE) {
            result.append(AbstractReader.NEW_LINE);
          }
          this.setReuseBuffer();
          continue;
        }
        this.setReuseBuffer();
        int character = this.readEscapedCharacter();
        if (!Character.isValidCodePoint(character)) {
          throw new IllegalArgumentException(String.format("Not a valid Unicode code point: 0x%X", character));
        }
        result.appendCodePoint(character);
      } else {
        result.append(marker);
      }
    }
  }

  private char readQuotedIndentation() {
    char marker;
    do {
      marker = this.readRaw();
    } while (marker == ' ' || marker == '\t');
    return marker;
  }

  private void readMultilineStringFromMarker(@Nullable Field owner, char marker, StringBuilder result) {
    boolean literal = marker == '|';
    if (!literal && marker != '>') {
      throw new IllegalStateException("Invalid multiline marker: " + marker);
    }
    if (this.yamlSerializable != null && owner != null) {
      this.saveStringStyle(owner, literal
          ? YamlWriter.StringStyle.MULTILINE_LITERAL_AUTO_CLIPPED : YamlWriter.StringStyle.MULTILINE_FOLDED_AUTO_CLIPPED);
    }

    int parentIndent = this.nodeIndent;
    char chomping = AbstractReader.NEW_LINE;
    int fixedIndent = 0;
    marker = this.readRaw();
    while (marker == '+' || marker == '-' || marker >= '1' && marker <= '9') {
      if (marker == '+' || marker == '-') {
        if (chomping != AbstractReader.NEW_LINE) {
          throw new IllegalStateException("Duplicate chomping indicator");
        }
        chomping = marker;
      } else {
        if (fixedIndent != 0) {
          throw new IllegalStateException("Duplicate indentation indicator");
        }
        fixedIndent = marker - '0';
      }
      marker = this.readRaw();
    }
    boolean separated = Character.isWhitespace(marker);
    while (marker == ' ' || marker == '\t') {
      marker = this.readRaw();
    }
    if (marker == '#' && separated) {
      do {
        marker = this.readRaw();
      } while (!this.isEndMarker(marker));
    }
    if (!this.isEndMarker(marker)) {
      throw new IllegalStateException("Got illegal marker while reading multiline string: " + marker);
    }
    if (marker == '\0') {
      return;
    }

    int newLineCount = 0;
    boolean content = false;
    boolean previousIndented = false;
    marker = this.readBlockLineStart();
    while (true) {
      int indentOffset = (marker == AbstractReader.NEW_LINE ? this.newLineIndent : this.currentIndent) - parentIndent;
      if (marker == '\0' && (fixedIndent == 0 || indentOffset <= fixedIndent)) {
        break;
      }
      if (marker == AbstractReader.NEW_LINE && (fixedIndent == 0 || indentOffset <= fixedIndent)) {
        ++newLineCount;
        marker = this.readBlockLineStart();
        continue;
      }
      if (indentOffset <= 0 || fixedIndent != 0 && indentOffset < fixedIndent) {
        break;
      }
      if (fixedIndent == 0) {
        fixedIndent = indentOffset;
      }
      boolean indented = indentOffset > fixedIndent || marker == '\t';
      if (!literal && content && !previousIndented && !indented) {
        if (newLineCount == 1) {
          result.append(' ');
        }
        newLineCount = Math.max(0, newLineCount - 1);
      }
      result.append("\n".repeat(newLineCount));
      result.append(" ".repeat(indentOffset - fixedIndent));
      while (!this.isEndMarker(marker)) {
        result.append(marker);
        marker = this.readRaw();
      }
      content = true;
      previousIndented = indented;
      newLineCount = marker == AbstractReader.NEW_LINE ? 1 : 0;
      if (marker == '\0') {
        break;
      }
      marker = this.readBlockLineStart();
    }
    if (chomping == '-') {
      newLineCount = 0;
    } else if (chomping != '+') {
      newLineCount = content ? Math.min(1, newLineCount) : 0;
    }
    result.append("\n".repeat(newLineCount));
    this.setReuseBuffer();
  }

  private char readBlockLineStart() {
    char marker;
    do {
      marker = this.readRaw();
    } while (marker == ' ');
    return marker;
  }

  @Override
  public void skipGuessingType(@Nullable Field owner) {
    synchronized (this) {
      this.skipGuessingTypeFromMarker(owner, this.readRawIgnoreEmpty());
    }
  }

  private void skipGuessingTypeFromMarker(@Nullable Field owner, char marker) {
    this.readGuessingTypeFromMarker(null, marker);
  }

  @Override
  public void skipCollection(@Nullable Field owner) {
    synchronized (this) {
      this.skipCollectionFromMarker(owner, this.readRawIgnoreEmpty());
    }
  }

  private void skipCollectionFromMarker(@Nullable Field owner, char marker) {
    this.readCollectionFromMarker(null, new ArrayList<>(), Object.class, marker);
  }

  @Override
  public void skipMap(@Nullable Field owner) {
    boolean startOfFile = this.startOfFile;
    synchronized (this) {
      char marker = this.readRawIgnoreEmpty();
      if (startOfFile) {
        this.setTempRestoreNewLine();
      }

      this.skipMapFromMarker(owner, marker);
    }
  }

  private void skipMapFromMarker(@Nullable Field owner, char marker) {
    this.readMapFromMarker(null, marker);
  }

  @Override
  public void skipString(@Nullable Field owner) {
    synchronized (this) {
      this.skipStringFromMarker(owner, this.readRawIgnoreEmpty(), false);
    }
  }

  private void skipStringFromMarker(@Nullable Field owner, char marker, boolean nodeName) {
    switch (marker) {
      case '"' -> {
        while ((marker = this.readQuotedMarker()) != '"') {
          if (marker == '\\') {
            this.readRaw(); // To ensure the reading doesn't stop at \"
          } else if (nodeName && this.isEndMarker(marker)) {
            throw new IllegalStateException("Got a new line in node name.");
          }
        }

        if (nodeName) {
          marker = this.readRawIgnoreEmpty();
          if (marker != ':') {
            throw new IllegalStateException("Got illegal marker when reading node name: " + marker);
          }
        } else {
          this.readQuotedValueEnd(owner);
        }
      }
      case '\'' -> {
        do {
          marker = this.readQuotedMarker();
          if (nodeName && this.isEndMarker(marker)) {
            throw new IllegalStateException("Got a new line in node name.");
          }
        } while (marker != '\'' || this.readRaw() == '\''); // 'text1 ''text2'' text3' reads as "text 'text2' text3".
        this.setReuseBuffer();
        if (nodeName) {
          marker = this.readRawIgnoreEmpty();
          if (marker != ':') {
            throw new IllegalStateException("Got illegal marker when reading node name: " + marker);
          }
        } else {
          this.readQuotedValueEnd(owner);
        }
      }
      default -> {
        if (marker == '#') {
          this.skipComments(owner, marker, false);
          break;
        }

        if (!nodeName && (marker == '|' || marker == '>')) {
          this.skipMultilineStringFromMarker(marker);
          break;
        }

        while (nodeName
            ? (marker != ':')
            // Here we don't need to care about bad comments, so we can ignore whitespace check, see YamlReader#skipComments(char, boolean) for details.
            : (!this.isEndMarker(marker) && !this.isFlowDelimiter(marker) && !this.skipComments(owner, marker, false))) {
          if (nodeName && this.isEndMarker(marker)) {
            throw new IllegalStateException("Got a new line in node name.");
          }

          marker = this.readRaw();
        }

        if (!nodeName && this.isFlowDelimiter(marker)) {
          this.setReuseBuffer();
        }
      }
    }
  }

  private void skipMultilineStringFromMarker(char marker) {
    this.readMultilineStringFromMarker(null, marker, new StringBuilder());
  }

  @Override
  public boolean skipComments(@Nullable Field owner, char marker, boolean reuse) {
    synchronized (this) {
      if (marker == '#') {
        while (true) {
          if (this.isEndMarker(this.readRaw())) {
            break;
          }
        }

        this.readRawIgnoreEmptyAndNewLines();
        this.setReuseBuffer();
        return true;
      }

      // Need in case if we're parsing plain string without quotes.
      // (e.g. `string: string#bad comment` would be parsed as "text#bad comment", otherwise `string: text #normal comment` would be parsed as "text",
      // see default case in YamlReader#readStringFromMarker(char, boolean))
      if (reuse) {
        this.setReuseBuffer();
      }

      return false;
    }
  }

  protected boolean isEndMarker(char marker) {
    return marker == '\0' || marker == AbstractReader.NEW_LINE;
  }

  private boolean isFlowDelimiter(char marker) {
    return this.bracketOpened && (marker == ',' || marker == ']' || marker == '}');
  }

  private void readQuotedValueEnd(@Nullable Field owner) {
    char marker = this.readRawIgnoreEmpty();
    if (this.isFlowDelimiter(marker)) {
      this.setReuseBuffer();
    } else if (!this.isEndMarker(marker) && !this.skipComments(owner, marker, false)) {
      throw new IllegalStateException("Got illegal marker after quoted value: " + marker);
    }
  }

  private char readQuotedMarker() {
    char marker = this.readRaw();
    if (marker == '\0') {
      throw new IllegalStateException("Unexpected end of input in quoted value");
    }

    return marker;
  }

  private char readFlowMarker(@Nullable Field owner) {
    char marker = this.readContentMarker(owner);
    if (marker == '\0') {
      throw new IllegalStateException("Unexpected end of input in flow collection");
    }

    return marker;
  }

  private char readContentMarker(@Nullable Field owner) {
    char marker = this.readRawIgnoreEmptyAndNewLines();
    while (this.skipComments(owner, marker, false)) {
      marker = this.readRawIgnoreEmptyAndNewLines();
    }

    return marker;
  }

  @Override
  protected boolean tryReadNull(@Nullable Field owner) {
    boolean document = this.startOfFile;
    char marker = this.readRawIgnoreEmpty();
    if (marker == 'n') {
      boolean result = this.isNullSkippedFromMarker(marker, true);
      if (!result) {
        this.startOfFile = document;
      }
      return result;
    }
    if (this.skipComments(owner, marker, false)) {
      marker = AbstractReader.NEW_LINE;
    }
    if (marker == AbstractReader.NEW_LINE) {
      char nextMarker = this.readContentMarker(owner);
      boolean absent = nextMarker == '\0' || this.currentIndent <= this.nodeIndent;
      this.setReuseBuffer();
      this.startOfFile = document;
      return absent;
    }
    this.setReuseBuffer();
    this.startOfFile = document;
    return marker == '\0';
  }

  private void readDocumentEnd(@Nullable Field owner) {
    if (this.readContentMarker(owner) != '\0') {
      throw new IllegalStateException("Unexpected content after the root value");
    }
  }

  private char readFlowSeparator(@Nullable Field owner, char closingMarker) {
    char marker = this.readFlowMarker(owner);
    if (marker == ',') {
      char nextMarker = this.readFlowMarker(owner);
      if (nextMarker == ',') {
        throw new IllegalStateException("Missing entry in flow collection");
      }
      return nextMarker;
    } else if (marker != closingMarker) {
      throw new IllegalStateException("Expected ',' or '" + closingMarker + "' in flow collection, got: " + marker);
    }

    return marker;
  }

  @Override
  public void setSeek() {
    synchronized (this) {
      this.seekIndent = this.currentIndent;
    }

    super.setSeek();
  }

  @Override
  public void setSeekFromMarker(char marker) {
    synchronized (this) {
      this.seekIndent = this.currentIndent - 1;
    }

    super.setSeekFromMarker(marker);
  }

  @Override
  public void unsetSeek() {
    synchronized (this) {
      this.currentIndent = this.seekIndent;
    }

    super.unsetSeek();
  }

  @Override
  public char readRaw() {
    this.startOfFile = false;
    synchronized (this) {
      boolean shouldIndent = !this.isReuseBuffer();
      char character = super.readRaw();
      if (character == AbstractReader.NEW_LINE) {
        this.newLineIndent = this.currentIndent + 1;
        this.currentIndent = 0;
      } else if (shouldIndent) {
        ++this.currentIndent;
      }

      return character;
    }
  }

  private boolean isNullSkippedFromMarker(char marker) {
    return this.isNullSkippedFromMarker(marker, false);
  }

  private boolean isNullSkippedFromMarker(char marker, boolean restoreMarker) {
    if (marker != 'n') {
      return false;
    }
    if (restoreMarker) {
      this.setSeekFromMarker(marker);
    } else {
      this.setSeek();
    }
    if (this.readRaw() == 'u' && this.readRaw() == 'l' && this.readRaw() == 'l') {
      char endMarker = this.readRawIgnoreEmpty();
      if (this.isEndMarker(endMarker) || this.isFlowDelimiter(endMarker) || this.skipComments(null, endMarker, false)) {
        this.clearSeek();
        if (this.isFlowDelimiter(endMarker)) {
          this.setReuseBuffer();
        }
        return true;
      }
    }

    this.unsetSeek();
    return false;
  }

  private boolean skipChar(char expected) {
    if (expected == this.readRawIgnoreEmptyAndNewLines()) {
      return true;
    } else {
      this.setReuseBuffer();
      return false;
    }
  }

  private void setTempRestoreNewLine() {
    this.tempRestoreNewLine = true;
  }

  private void unsetTempRestoreNewLine() {
    this.tempRestoreNewLine = false;
  }
}
