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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.elytrium.serializer.custom.ClassSerializer;
import net.elytrium.serializer.exceptions.SerializableWriteException;
import net.elytrium.serializer.language.object.YamlSerializable;
import net.elytrium.serializer.language.reader.AbstractReader;
import net.elytrium.serializer.language.writer.AbstractWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SerializableFileTest {

  @Test
  void backupCopiesDiskContentsWithoutSavingMemory(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("config.yml");
    String original = "# original comment\nvalue: original\n";
    Files.writeString(path, original);
    Settings settings = new Settings(path, SerializerConfig.DEFAULT);

    settings.backup();

    Assertions.assertEquals(original, Files.readString(path));
    List<Path> backups;
    try (Stream<Path> files = Files.list(directory)) {
      backups = files.filter(file -> file.getFileName().toString().startsWith("config.yml_backup_")).collect(Collectors.toList());
    }
    Assertions.assertEquals(1, backups.size());
    Assertions.assertEquals(original, Files.readString(backups.get(0)));
  }

  @Test
  void serializationFailurePreservesExistingFile(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("config.yml");
    String original = "# original comment\nvalue: original\n";
    Files.writeString(path, original);
    Settings settings = SerializableFileTest.failingSettings(path);

    Assertions.assertThrows(IllegalStateException.class, settings::save);

    Assertions.assertEquals(original, Files.readString(path));
    SerializableFileTest.assertOnlyFile(directory, path);
  }

  @Test
  void serializationFailureDoesNotCreateDestination(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("config.yml");
    Settings settings = SerializableFileTest.failingSettings(path);

    Assertions.assertThrows(IllegalStateException.class, settings::save);

    Assertions.assertFalse(Files.exists(path));
    try (Stream<Path> files = Files.list(directory)) {
      Assertions.assertEquals(0, files.count());
    }
  }

  @Test
  void failureAfterFlushingOutputPreservesExistingFile(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, "value: original\n");
    YamlSerializable settings = new YamlSerializable() {
      @Override
      public void save(BufferedWriter writer) {
        try {
          writer.write("value: partially written\n");
          writer.flush();
        } catch (IOException e) {
          throw new SerializableWriteException(e);
        }
        throw new IllegalStateException("Failure after flushing output");
      }
    };

    Assertions.assertThrows(IllegalStateException.class, () -> settings.save(path));

    Assertions.assertEquals("value: original\n", Files.readString(path));
    SerializableFileTest.assertOnlyFile(directory, path);
  }

  @Test
  void savePreservesSymbolicLink(@TempDir Path directory) throws IOException {
    Path target = directory.resolve("target.yml");
    Files.writeString(target, "value: original\n");
    Path link = directory.resolve("config.yml");
    try {
      Files.createSymbolicLink(link, target.getFileName());
    } catch (UnsupportedOperationException | FileSystemException e) {
      Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
    }
    Settings settings = new Settings(link, SerializerConfig.DEFAULT);

    settings.save();

    Assertions.assertTrue(Files.isSymbolicLink(link));
    Settings loaded = new Settings(target, SerializerConfig.DEFAULT);
    loaded.load();
    Assertions.assertEquals("memory", loaded.value);
    try (Stream<Path> files = Files.list(directory)) {
      Assertions.assertEquals(2, files.count());
    }
  }

  @Test
  void savePreservesPosixPermissions(@TempDir Path directory) throws IOException {
    Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    Path path = directory.resolve("config.yml");
    Files.writeString(path, "value: original\n");
    Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r-----");
    Files.setPosixFilePermissions(path, permissions);
    Settings settings = new Settings(path, SerializerConfig.DEFAULT);

    settings.save();

    Assertions.assertEquals(permissions, Files.getPosixFilePermissions(path));
  }

  @Test
  void savePreservesAclWhileWritingTemporaryData(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, "value: original\n");
    AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
    Assumptions.assumeTrue(view != null);
    view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
        .setPrincipal(view.getOwner()).setPermissions(AclEntryPermission.values()).build()));
    List<AclEntry> permissions = view.getAcl();
    YamlSerializable settings = new YamlSerializable() {
      @Override
      public void save(BufferedWriter writer) {
        try {
          writer.write("value: memory\n");
          writer.flush();
          try (Stream<Path> files = Files.list(directory)) {
            Path writtenFile = files.filter(file -> !file.equals(path)).findFirst().orElse(path);
            Assertions.assertEquals(permissions, Files.getFileAttributeView(writtenFile, AclFileAttributeView.class).getAcl());
          }
        } catch (IOException e) {
          throw new SerializableWriteException(e);
        }
      }
    };

    settings.save(path);

    Assertions.assertEquals(permissions, Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl());
    Assertions.assertEquals("value: memory\n", Files.readString(path));
    SerializableFileTest.assertOnlyFile(directory, path);
  }

  @Test
  void savePreservesHiddenAndSystemAttributes(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, "value: original\n");
    DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class);
    Assumptions.assumeTrue(view != null);
    view.setHidden(true);
    view.setSystem(true);
    try {
      Settings settings = new Settings(path, SerializerConfig.DEFAULT);
      settings.save();

      Assertions.assertTrue(Files.getFileAttributeView(path, DosFileAttributeView.class).readAttributes().isHidden());
      Assertions.assertTrue(Files.getFileAttributeView(path, DosFileAttributeView.class).readAttributes().isSystem());
      Settings loaded = new Settings(path, SerializerConfig.DEFAULT);
      loaded.load();
      Assertions.assertEquals("memory", loaded.value);
      SerializableFileTest.assertOnlyFile(directory, path);

      String saved = Files.readString(path);
      Assertions.assertThrows(IllegalStateException.class, SerializableFileTest.failingSettings(path)::save);
      Assertions.assertEquals(saved, Files.readString(path));
      Assertions.assertTrue(Files.getFileAttributeView(path, DosFileAttributeView.class).readAttributes().isHidden());
      Assertions.assertTrue(Files.getFileAttributeView(path, DosFileAttributeView.class).readAttributes().isSystem());
      SerializableFileTest.assertOnlyFile(directory, path);
    } finally {
      Files.getFileAttributeView(path, DosFileAttributeView.class).setHidden(false);
      Files.getFileAttributeView(path, DosFileAttributeView.class).setSystem(false);
    }
  }

  @Test
  void successfulSaveReplacesFileAndRemovesTemporaryFile(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, "value: original\n");
    Settings settings = new Settings(path, SerializerConfig.DEFAULT);

    settings.save();

    Settings loaded = new Settings(path, SerializerConfig.DEFAULT);
    Assertions.assertEquals(LoadResult.SUCCESS, loaded.load());
    Assertions.assertEquals("memory", loaded.value);
    SerializableFileTest.assertOnlyFile(directory, path);
  }

  @Test
  void saveCreatesMissingParentDirectories(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("nested/config.yml");
    Settings settings = new Settings(path, SerializerConfig.DEFAULT);

    settings.save();

    Assertions.assertTrue(Files.isRegularFile(path));
    SerializableFileTest.assertOnlyFile(path.getParent(), path);
  }

  @Test
  void failedReplacementPreservesDestinationAndRemovesTemporaryFile(@TempDir Path directory) throws IOException {
    Path path = Files.createDirectory(directory.resolve("config.yml"));
    Path child = path.resolve("keep.txt");
    Files.writeString(child, "keep");
    Settings settings = new Settings(path, SerializerConfig.DEFAULT);

    Assertions.assertThrows(SerializableWriteException.class, settings::save);

    Assertions.assertEquals("keep", Files.readString(child));
    SerializableFileTest.assertOnlyFile(directory, path);
  }

  @Test
  void pathSaveClosesItsWriterOnSuccessAndFailure(@TempDir Path directory) {
    Settings success = new Settings(directory.resolve("success.yml"), SerializerConfig.DEFAULT);
    success.save();
    Assertions.assertThrows(IOException.class, () -> success.openedWriter.write("extra"));

    Settings failure = SerializableFileTest.failingSettings(directory.resolve("failure.yml"));
    Assertions.assertThrows(IllegalStateException.class, failure::save);
    Assertions.assertThrows(IOException.class, () -> failure.openedWriter.write("extra"));
  }

  @Test
  void pathLoadClosesItsReaderOnSuccessAndFailure(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("config.yml");
    Settings settings = new Settings(path, SerializerConfig.DEFAULT);
    Files.writeString(path, "value: original\n");
    settings.load();
    Assertions.assertThrows(IOException.class, () -> settings.openedReader.read());

    Files.writeString(path, "value: \"unterminated");
    Assertions.assertThrows(IllegalStateException.class, settings::load);
    Assertions.assertThrows(IOException.class, () -> settings.openedReader.read());
  }

  @Test
  void reloadCanReplaceTheFileItJustRead(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("config.yml");
    Files.writeString(path, "value: original\n");
    Settings settings = new Settings(path, SerializerConfig.DEFAULT);

    Assertions.assertEquals(LoadResult.SUCCESS, settings.reload());
    Assertions.assertEquals("original", settings.value);
    SerializableFileTest.assertOnlyFile(directory, path);
  }

  @Test
  void callerOwnedReaderAndWriterRemainOpen() throws IOException {
    Settings settings = new Settings(null, SerializerConfig.DEFAULT);
    try (BufferedReader reader = new BufferedReader(new StringReader("value: original\n"));
        BufferedWriter writer = new BufferedWriter(new StringWriter())) {
      settings.load(reader);
      Assertions.assertEquals(-1, reader.read());
      settings.save(writer);
      writer.write("extra");
      writer.flush();
    }
  }

  private static Settings failingSettings(Path path) {
    SerializerConfig config = new SerializerConfig.Builder().registerSerializer(new FailingSerializer()).build();
    return new Settings(path, config);
  }

  private static void assertOnlyFile(Path directory, Path expected) throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      Assertions.assertEquals(List.of(expected), files.collect(Collectors.toList()));
    }
  }

  private static class Settings extends YamlSerializable {

    private String value = "memory";
    private transient BufferedReader openedReader;
    private transient BufferedWriter openedWriter;

    private Settings(Path path, SerializerConfig config) {
      super(path, config);
    }

    @Override
    protected AbstractReader getReader(BufferedReader reader) {
      this.openedReader = reader;
      return super.getReader(reader);
    }

    @Override
    protected AbstractWriter getWriter(BufferedWriter writer) {
      this.openedWriter = writer;
      return super.getWriter(writer);
    }
  }

  private static class FailingSerializer extends ClassSerializer<String, String> {

    @Override
    public String serialize(String value) {
      throw new IllegalStateException("Serialization failed");
    }
  }
}
