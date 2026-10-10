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
import java.lang.reflect.Field;
import net.elytrium.serializer.SerializerConfig;
import net.elytrium.serializer.annotations.Comment;
import org.jetbrains.annotations.Nullable;

public class JsonWriter extends YamlWriter {

  private String singleIndent = "  ";
  private String currentIndent = "";

  public JsonWriter(BufferedWriter writer, SerializerConfig config) {
    super(config, writer);
  }

  public JsonWriter(BufferedWriter writer) {
    super(writer);
  }

  @Override
  public void writeComment(@Nullable Field owner, Comment comment, boolean shouldJoin) {
    // JSON closing delimiters must never share a line with an APPEND line comment.
    super.writeComment(owner, comment, shouldJoin || comment.at() == Comment.At.APPEND);
  }

  @Override
  public void writeCommentStart(@Nullable Field owner, Comment.At at) {
    synchronized (this) {
      if (at != Comment.At.SAME_LINE) {
        this.writeIndent();
      }

      this.writeRaw("//");
    }
  }

  @Override
  public void writeCommentEnd(@Nullable Field owner, Comment.At at) {
    synchronized (this) {
      if (at != Comment.At.SAME_LINE) {
        this.writeLine();
      }
    }
  }

  @Override
  public void writeNodeName(@Nullable Field owner, String nodeName) {
    synchronized (this) {
      this.writeIndent();
      this.writeString(owner, nodeName);
      this.writeRaw(": ");
    }
  }

  @Override
  protected void writeMapKey(@Nullable Field owner, Object key) {
    this.writeNodeName(owner, String.valueOf(key));
  }

  @Override
  protected String getMapKeyIdentity(Object key) {
    return String.valueOf(key);
  }

  @Override
  public void writeString(@Nullable Field owner, String value) {
    synchronized (this) {
      this.writeRaw('"');
      for (int i = 0; i < value.length(); ++i) {
        char character = value.charAt(i);
        switch (character) {
          case '"' -> this.writeRaw("\\\"");
          case '\\' -> this.writeRaw("\\\\");
          case '\b' -> this.writeRaw("\\b");
          case '\f' -> this.writeRaw("\\f");
          case '\n' -> this.writeRaw("\\n");
          case '\r' -> this.writeRaw("\\r");
          case '\t' -> this.writeRaw("\\t");
          default -> {
            if (character < 0x20 || Character.isSurrogate(character)) {
              String hex = "000" + Integer.toHexString(character);
              this.writeRaw("\\u" + hex.substring(hex.length() - 4));
            } else {
              this.writeRaw(character);
            }
          }
        }
      }
      this.writeRaw('"');
    }
  }

  @Override
  public void writeCharacter(@Nullable Field owner, char value) {
    this.writeString(owner, String.valueOf(value));
  }

  @Override
  public void writeEnum(@Nullable Field owner, Enum<?> value) {
    this.writeString(owner, value.name());
  }

  /**
   * Writes a JSON number, rejecting NaN and infinity because JSON has no representation for them.
   */
  @Override
  public void writeNumber(@Nullable Field owner, Number value) {
    if ((value instanceof Double && !Double.isFinite(value.doubleValue()))
        || (value instanceof Float && !Float.isFinite(value.floatValue()))) {
      throw new IllegalArgumentException("JSON numbers must be finite: " + value);
    }
    super.writeNumber(owner, value);
  }

  @Override
  public void writeBeginMap(@Nullable Field owner) {
    synchronized (this) {
      this.addIndent();
      this.writeRaw('{');
      this.writeLine();
    }
  }

  @Override
  public void writeMapPreCommentEntryJoin(@Nullable Field owner) {
    synchronized (this) {
      this.writeRaw(',');
    }
  }

  @Override
  public void writeMapPostCommentEntryJoin(@Nullable Field owner) {

  }

  @Override
  public void writeMapEntryEnd(@Nullable Field owner) {
    synchronized (this) {
      this.writeLine();
    }
  }

  @Override
  public void writeEndMap(@Nullable Field owner) {
    synchronized (this) {
      this.removeIndent();
      this.writeIndent();
      this.writeRaw('}');
    }
  }

  @Override
  public void writeBeginCollection(@Nullable Field owner) {
    synchronized (this) {
      this.addIndent();
      this.writeRaw('[');
      this.writeLine();
    }
  }

  @Override
  public void writeCollectionEntry(@Nullable Field owner, Object entry) {
    synchronized (this) {
      this.writeIndent();
      this.writeNode(entry, null);
    }
  }

  @Override
  public void writeCollectionEntryJoin(@Nullable Field owner) {
    synchronized (this) {
      this.writeRaw(',');
    }
  }

  @Override
  public void writeCollectionEntryEnd(@Nullable Field owner) {
    synchronized (this) {
      this.writeLine();
    }
  }

  @Override
  public void writeEndCollection(@Nullable Field owner) {
    synchronized (this) {
      this.removeIndent();
      this.writeIndent();
      this.writeRaw(']');
    }
  }

  @Override
  public void writeLine() {
    synchronized (this) {
      super.writeRaw(this.config.getLineSeparator());
    }
  }

  public void setSingleIndent(String singleIndent) {
    this.singleIndent = singleIndent;
  }

  private void writeIndent() {
    this.writeRaw(this.currentIndent);
  }

  private void addIndent() {
    this.currentIndent += this.singleIndent;
  }

  private void removeIndent() {
    this.currentIndent = this.currentIndent.substring(this.singleIndent.length());
  }
}
