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

package net.elytrium.serializer.language.writer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.elytrium.serializer.SerializerConfig;
import net.elytrium.serializer.annotations.Comment;
import net.elytrium.serializer.annotations.CommentValue;
import net.elytrium.serializer.annotations.FallbackNodeNames;
import net.elytrium.serializer.annotations.NewLine;
import net.elytrium.serializer.annotations.OverrideNameStyle;
import net.elytrium.serializer.annotations.Serializer;
import net.elytrium.serializer.annotations.Transient;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.exceptions.ReflectionException;
import net.elytrium.serializer.exceptions.SerializableWriteException;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractWriter {

  protected static final char NEW_LINE = '\n';

  protected final SerializerConfig config;
  protected final BufferedWriter writer;

  private boolean first = true;

  protected AbstractWriter(SerializerConfig config, BufferedWriter writer) {
    this.config = config;
    this.writer = writer;
  }

  protected AbstractWriter(BufferedWriter writer) {
    this.config = SerializerConfig.DEFAULT;
    this.writer = writer;
  }

  public void writeSerializableObject(Object value, Class<?> clazz) {
    this.writeSerializableObject(null, value, clazz);
  }

  public void writeSerializableObject(@Nullable Field owner, Object value, Class<?> clazz) {
    synchronized (this) {
      Field[] fields = clazz.getDeclaredFields();
      Map<Field, String> nodeNames = new HashMap<>();
      Map<String, Field> visibleNames = new HashMap<>();
      for (Field field : fields) {
        if (this.isFieldVisible(field)) {
          String nodeName = this.getNodeName(field);
          this.registerNodeName(visibleNames, nodeName, field);
          FallbackNodeNames aliases = field.getAnnotation(FallbackNodeNames.class);
          if (aliases != null) {
            for (String alias : aliases.value()) {
              this.registerNodeName(visibleNames, alias, field);
            }
          }
          nodeNames.put(field, nodeName);
        }
      }

      boolean first = false;
      if (this.first) {
        first = true;
        this.first = false;
      }

      boolean empty = true;
      int counter = 0;
      int lastVisibleFieldIndex = 0;
      for (int i = fields.length - 1; i >= 0; --i) {
        if (nodeNames.containsKey(fields[i])) {
          lastVisibleFieldIndex = i + 1;
          break;
        }
      }

      for (Field field : fields) {
        ++counter;
        if (nodeNames.containsKey(field)) {
          try {
            NewLine newLines = field.getAnnotation(NewLine.class);
            if (newLines == null) {
              newLines = field.getType().getAnnotation(NewLine.class);
            }

            if (newLines != null) {
              for (int i = newLines.amount() - 1; i >= 1; --i) {
                this.writeRaw(this.config.getLineSeparator());
              }

              this.writeLine();
            }

            final Object nodeValue = field.get(value);
            if (empty) {
              empty = false;
              this.writeBeginMap(owner);
            }

            Comment[] comments;
            Comment[] classComments = field.getType().getAnnotationsByType(Comment.class);
            Comment[] fieldComments = field.getAnnotationsByType(Comment.class);
            if (classComments.length == 0 || fieldComments.length == 0) {
              comments = classComments.length == 0 ? fieldComments : classComments;
            } else {
              comments = new Comment[classComments.length + fieldComments.length];
              System.arraycopy(classComments, 0, comments, 0, classComments.length);
              System.arraycopy(fieldComments, 0, comments, classComments.length, fieldComments.length);
            }

            this.writeMapEntry(
                field,
                nodeNames.get(field),
                nodeValue,
                counter != lastVisibleFieldIndex,
                comments
            );
          } catch (ReflectiveOperationException e) {
            throw new ReflectionException(e);
          }
        }
      }

      if (empty) {
        this.writeEmptyMap();
      } else {
        this.writeEndMap(owner);
      }

      if (first) {
        this.writeLine();
      }
    }
  }

  private void registerNodeName(Map<String, Field> fields, String name, Field field) {
    Field previous = fields.putIfAbsent(name, field);
    if (previous != null && !previous.equals(field)) {
      throw new IllegalArgumentException("Fields " + previous + " and " + field + " collide at node name: " + name);
    }
  }

  private String getNodeName(Field field) {
    OverrideNameStyle overrideNameStyle = field.getAnnotation(OverrideNameStyle.class);
    if (overrideNameStyle == null) {
      overrideNameStyle = field.getType().getAnnotation(OverrideNameStyle.class);
    }
    return overrideNameStyle == null ? this.config.toNodeName(field.getName())
        : this.config.toNodeName(field.getName(), overrideNameStyle.field(), overrideNameStyle.node());
  }

  private boolean isFieldVisible(Field field) {
    try {
      field.setAccessible(true);
    } catch (Exception e) {
      return false;
    }

    int modifiers = field.getModifiers();
    return !Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers) && field.getAnnotation(Transient.class) == null && field.getType().getAnnotation(Transient.class) == null;
  }

  private Object serializeValue(@Nullable Field owner, Object nodeValue) {
    if (nodeValue == null) {
      return null;
    }
    Set<ClassSerializer<?, ?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<Type> visitedTypes = new HashSet<>();
    ClassSerializer<Object, ?> classSerializer = null;
    Serializer annotation = owner == null ? null : owner.getAnnotation(Serializer.class);
    if (annotation == null) {
      Class<?> annotatedClass = owner == null
          ? nodeValue instanceof Enum<?> enumValue ? enumValue.getDeclaringClass() : nodeValue.getClass()
          : owner.getType();
      annotation = annotatedClass.getAnnotation(Serializer.class);
    }
    if (annotation != null) {
      try {
        classSerializer = this.config.getAndCacheSerializer(annotation);
      } catch (ReflectiveOperationException e) {
        throw new ReflectionException(e);
      }
    }
    boolean initialStage = true;
    while (nodeValue != null) {
      if (classSerializer == null) {
        Class<?> lookupClass = initialStage && owner != null && owner.getType().isPrimitive() ? owner.getType() : nodeValue.getClass();
        classSerializer = this.config.getRegisteredSerializer(lookupClass);
      }
      if (classSerializer == null) {
        break;
      }
      if (!visited.add(classSerializer)) {
        // Its wire type inherited a broader registration, e.g. Object -> String.
        break;
      }
      visitedTypes.add(classSerializer.getToType());
      nodeValue = classSerializer.serialize(nodeValue);
      // Registry lookup uses raw classes, so a same-raw generic conversion is a terminal stage too.
      if (classSerializer.getToClass().equals(classSerializer.getFromClass())) {
        break;
      }
      if (!visitedTypes.add(classSerializer.getFromType())) {
        throw new IllegalStateException("Cyclic serializer chain at " + classSerializer.getClass().getName());
      }
      classSerializer = null;
      initialStage = false;
    }

    return nodeValue;
  }

  public void writeMapEntry(String nodeName, Object node, boolean shouldJoin, Comment[] comments) {
    this.writeMapEntry(null, nodeName, node, shouldJoin, comments);
  }

  public void writeMapEntry(@Nullable Field owner, String nodeName, Object node, boolean shouldJoin, Comment[] comments) {
    this.writeMapEntry0(owner, nodeName, node, shouldJoin, comments);
  }

  private void writeMapEntry0(@Nullable Field owner, Object key, Object node, boolean shouldJoin, Comment[] comments) {
    synchronized (this) {
      this.writeComments(owner, comments, Comment.At.PREPEND, true);
      this.writeMapKey(owner, key);
      boolean commentsWritten = this.writeNodeWithComments(owner, node, comments);
      if (shouldJoin) {
        this.writeMapPreCommentEntryJoin(owner);
      }
      if (!commentsWritten) {
        this.writeComments(owner, comments, Comment.At.SAME_LINE, true);
      }
      if (shouldJoin) {
        this.writeMapPostCommentEntryJoin(owner);
      }
      this.writeMapEntryEnd(owner);
      this.writeComments(owner, comments, Comment.At.APPEND, shouldJoin);
    }
  }

  protected void writeMapKey(@Nullable Field owner, Object key) {
    this.writeNodeName(owner, String.valueOf(key));
  }

  protected String getMapKeyIdentity(Object key) {
    return String.valueOf(key);
  }

  protected boolean writeNodeWithComments(@Nullable Field owner, Object value, Comment[] comments) {
    this.writeNode(owner, value, null);
    return false;
  }

  public void writeComments(Comment[] comments, Comment.At currentPosition, boolean shouldJoin) {
    this.writeComments(null, comments, currentPosition, shouldJoin);
  }

  public void writeComments(@Nullable Field owner, Comment[] comments, Comment.At currentPosition, boolean shouldJoin) {
    synchronized (this) {
      if (comments != null && comments.length != 0) {
        for (int i = 0; i < comments.length - 1; ++i) {
          if (currentPosition == comments[i].at()) {
            this.writeComment(owner, comments[i], true);
          }
        }

        if (currentPosition == comments[comments.length - 1].at()) {
          this.writeComment(owner, comments[comments.length - 1], shouldJoin);
        }
      }
    }
  }

  public void writeComment(Comment comment, boolean shouldJoin) {
    this.writeComment(null, comment, shouldJoin);
  }

  public void writeComment(@Nullable Field owner, Comment comment, boolean shouldJoin) {
    synchronized (this) {
      for (CommentValue line : comment.value()) {
        if (line.type() == CommentValue.Type.NEW_LINE) {
          this.writeCommentEnd(owner, comment.at());
        } else {
          if (!shouldJoin) {
            this.writeLine();
          }
          this.writeCommentStart(owner, comment.at());
          this.writeCommentValueIndent(owner, comment.at(), this.getCommentValueIndent(comment, line));
          this.writeRaw(line.value());
          if (shouldJoin) {
            this.writeCommentEnd(owner, comment.at());
          }
        }
      }
    }
  }

  private int getCommentValueIndent(Comment comment, CommentValue value) {
    if (value.commentValueIndent() != -1) {
      return value.commentValueIndent();
    }

    if (comment.commentValueIndent() != -1) {
      return comment.commentValueIndent();
    }

    return this.config.getCommentValueIndent();
  }

  public void writeCommentStart(Comment.At at) {
    this.writeCommentStart(null, at);
  }

  public abstract void writeCommentStart(@Nullable Field owner, Comment.At at);

  public void writeCommentValueIndent(Comment.At at, int indent) {
    this.writeCommentValueIndent(null, at, indent);
  }

  public abstract void writeCommentValueIndent(@Nullable Field owner, Comment.At at, int indent);

  public void writeCommentEnd(Comment.At at) {
    this.writeCommentEnd(null, at);
  }

  public abstract void writeCommentEnd(@Nullable Field owner, Comment.At at);

  public void writeNodeName(String nodeName) {
    this.writeNodeName(null, nodeName);
  }

  public abstract void writeNodeName(@Nullable Field owner, String nodeName);

  public void writeNode(Object value, Comment[] comments) {
    this.writeNode(null, value, comments);
  }

  @SuppressWarnings("unchecked")
  public void writeNode(@Nullable Field owner, Object value, Comment[] comments) {
    synchronized (this) {
      value = this.serializeValue(owner, value);
      if (value == null) {
        this.writeRaw("null");
      } else {
        if (value instanceof Map) {
          this.writeMap(owner, (Map<Object, Object>) value, comments);
        } else if (value instanceof Collection<?>) {
          this.writeCollection(owner, (Collection<Object>) value, comments);
        } else if (value instanceof String) {
          this.writeString(owner, (String) value);
        } else if (value instanceof Character) {
          this.writeCharacter(owner, (Character) value);
        } else if (value instanceof Enum) {
          this.writeEnum(owner, (Enum<?>) value);
        } else if (value instanceof Boolean) {
          this.writeBoolean(owner, (Boolean) value);
        } else if (value instanceof Number) {
          this.writeNumber(owner, (Number) value);
        } else {
          this.writeSerializableObject(owner, value, value.getClass());
        }
      }
    }
  }

  public void writeMap(Map<Object, Object> value, Comment[] comments) {
    this.writeMap(null, value, comments);
  }

  public void writeMap(@Nullable Field owner, Map<Object, Object> value, Comment[] comments) {
    synchronized (this) {
      this.first = false;
      if (value.isEmpty()) {
        this.writeEmptyMap(owner);
        this.writeComments(owner, comments, Comment.At.SAME_LINE, true);
      } else {
        this.writeBeginMap(owner);
        this.writeComments(owner, comments, Comment.At.SAME_LINE, true);

        Set<Map.Entry<Object, Object>> entries = value.entrySet();
        Set<String> writtenKeys = new HashSet<>();
        int counter = 0;
        int entriesAmount = entries.size();
        for (Map.Entry<Object, Object> entry : entries) {
          Object key = this.serializeValue(null, entry.getKey());
          if (!writtenKeys.add(this.getMapKeyIdentity(key))) {
            throw new IllegalArgumentException("Map keys collide after serialization: " + key);
          }
          this.writeMapEntry0(null, key, entry.getValue(), ++counter != entriesAmount, null);
        }

        this.writeEndMap(owner);
      }
    }
  }

  public void writeEmptyMap() {
    this.writeEmptyMap(null);
  }

  public abstract void writeEmptyMap(@Nullable Field owner);

  public void writeBeginMap() {
    this.writeBeginMap(null);
  }

  public abstract void writeBeginMap(@Nullable Field owner);

  public void writeMapPreCommentEntryJoin() {
    this.writeMapPreCommentEntryJoin(null);
  }

  public abstract void writeMapPreCommentEntryJoin(@Nullable Field owner);

  public void writeMapPostCommentEntryJoin() {
    this.writeMapPostCommentEntryJoin(null);
  }

  public abstract void writeMapPostCommentEntryJoin(@Nullable Field owner);

  public void writeMapEntryEnd() {
    this.writeMapEntryEnd(null);
  }

  public abstract void writeMapEntryEnd(@Nullable Field owner);

  public void writeEndMap() {
    this.writeEndMap(null);
  }

  public abstract void writeEndMap(@Nullable Field owner);

  public void writeCollection(Collection<Object> value, Comment[] comments) {
    this.writeCollection(null, value, comments);
  }

  public void writeCollection(@Nullable Field owner, Collection<Object> value, Comment[] comments) {
    synchronized (this) {
      this.first = false;
      if (value.isEmpty()) {
        this.writeEmptyCollection(owner);
        this.writeComments(owner, comments, Comment.At.SAME_LINE, true);
      } else {
        this.writeBeginCollection(owner);
        this.writeComments(owner, comments, Comment.At.SAME_LINE, true);

        int counter = 0;
        int entriesAmount = value.size();
        for (Object entry : value) {
          this.writeCollectionEntry(owner, entry);
          if (++counter != entriesAmount) {
            this.writeCollectionEntryJoin(owner);
          }

          this.writeCollectionEntryEnd(owner);
        }

        this.writeEndCollection(owner);
      }
    }
  }

  public void writeEmptyCollection() {
    this.writeEmptyCollection(null);
  }

  public abstract void writeEmptyCollection(@Nullable Field owner);

  public void writeBeginCollection() {
    this.writeBeginCollection(null);
  }

  public abstract void writeBeginCollection(@Nullable Field owner);

  public void writeCollectionEntry(Object entry) {
    this.writeCollectionEntry(null, entry);
  }

  public abstract void writeCollectionEntry(@Nullable Field owner, Object entry);

  public void writeCollectionEntryJoin() {
    this.writeCollectionEntryJoin(null);
  }

  public abstract void writeCollectionEntryJoin(@Nullable Field owner);

  public void writeCollectionEntryEnd() {
    this.writeCollectionEntryEnd(null);
  }

  public abstract void writeCollectionEntryEnd(@Nullable Field owner);

  public void writeEndCollection() {
    this.writeEndCollection(null);
  }

  public abstract void writeEndCollection(@Nullable Field owner);

  public void writeString(String value) {
    this.writeString(null, value);
  }

  public abstract void writeString(@Nullable Field owner, String value);

  public void writeCharacter(char value) {
    this.writeCharacter(null, value);
  }

  public abstract void writeCharacter(@Nullable Field owner, char value);

  public void writeEnum(Enum<?> value) {
    this.writeEnum(null, value);
  }

  public void writeEnum(@Nullable Field owner, Enum<?> value) {
    synchronized (this) {
      this.writeRaw(value.name());
    }
  }

  public void writeBoolean(boolean value) {
    this.writeBoolean(null, value);
  }

  public void writeBoolean(@Nullable Field owner, boolean value) {
    synchronized (this) {
      this.writeRaw(String.valueOf(value));
    }
  }

  public void writeNumber(Number value) {
    this.writeNumber(null, value);
  }

  public void writeNumber(@Nullable Field owner, Number value) {
    synchronized (this) {
      this.writeRaw(value.toString());
    }
  }

  public abstract void writeLine();

  public void writeRaw(String value) {
    try {
      this.writer.write(value);
    } catch (IOException e) {
      throw new SerializableWriteException(e);
    }
  }

  public void writeRaw(char value) {
    try {
      this.writer.write(value);
    } catch (IOException e) {
      throw new SerializableWriteException(e);
    }
  }

  public void flush() {
    try {
      this.writer.flush();
    } catch (IOException e) {
      throw new SerializableWriteException(e);
    }
  }
}
